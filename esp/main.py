# ============================================================
#  POKEDEX SIECI  —  XIAO ESP32-C3 + akumulator  (MicroPython)
#  Skanuje WiFi, łapie nowe sieci, nadaje im imię, typ i rzadkość.
#  Kolekcję oglądasz w apce "Pokedex Sieci" przez Bluetooth.
# ============================================================
import network, bluetooth, time, json, machine, gc

# ---------- ustawienia ----------
SCAN_EVERY_S  = 45
BLE_NAME      = "Pokedex"
DEX_FILE      = "dex.json"
MAX_ENTRIES   = 400

RARITY_NAME  = ["Zwykła", "Rzadka", "Epicka", "LEGENDARNA"]

BORING = ["orange_", "orange-", "funbox", "upc", "tp-link", "tplink", "play_", "play-",
  "plus_", "t-mobile", "tmobile", "netia", "vectra", "inea", "direct-", "fritz", "huawei",
  "zte", "dlink", "d-link", "asus", "tenda", "linksys", "netgear", "mercusys", "hp-print",
  "eduroam", "multimedia", "internet_", "wifi_", "toya", "mikrotik", "xiaomi_"]
PHONE = ["iphone", "galaxy", "android", "redmi", "pixel", "poco", "oneplus", "motorola",
  "realme", "'s ", "’s "]
EPIC = ["5g", "6g", "7g", "8g", "maszt", "test", "fbi", "cia", "nsa", "abw", "policja",
  "police", "wirus", "virus", "hack", "rzad", "rząd", "nie lacz", "nie łącz", "free",
  "darmow", "kebab", "pizza", "szatan", "666", "420", "1337", "uwaga", "alien", "ufo",
  "van", "sasiad", "sąsiad", "haslo", "hasło", "mama", "babcia", "kot", "pies", "bomba",
  "tajne", "agent", "skynet", "matrix", "moc", "promieniow", "chip", "kurw", "jebac",
  "chuj", "papiez", "papież", "2137", "smierc", "śmierć", "zombie", "dron", "rakiet",
  "wifi dla", "ukradn"]
TYPES = ["Ogień", "Woda", "Trawa", "Psychiczny", "Mrok", "Stal", "Smok", "Trucizna"]
SUF = [["let", "ik", "ini", "us", "ek", "chu"],
       ["on", "ard", "ix", "eon", "ite", "ra"],
       ["ador", "orax", "ithan", "ozaur", "octor", "anox"],
       ["geddon", "zar", "thos", "mageddon", "oraptor", "kalipsa"]]
SYL = ["ka", "zu", "mo", "ri", "pi", "dra", "go", "ne", "ta", "lu", "be", "xo"]

# ---------- narzędzia ----------
def fnv(s):
    h = 2166136261
    for b in s.encode():
        h = ((h ^ b) * 16777619) & 0xFFFFFFFF
    return h

def epic_score(ssid, hidden, rssi):
    if hidden:
        return 3
    low = ssid.lower()
    s = 0
    for w in BORING:
        if w in low: s -= 4
    for w in EPIC:
        if w in low: s += 3
    if "%" in ssid: s += 2
    s += min(ssid.count("!") + ssid.count("?"), 3)
    sp = ssid.count(" ")
    s += 2 if sp >= 2 else (1 if sp == 1 else 0)      # ludzka nazwa
    if len(ssid) > 15: s += 1
    raw = ssid.encode()
    if any(b >= 0xF0 for b in raw): s += 3            # emoji
    if any(0xC4 <= b <= 0xC5 for b in raw): s += 1    # polskie znaki
    letters = [c for c in ssid if c.isalpha()]
    if len(letters) >= 5 and all(c.isupper() for c in letters): s += 2   # KRZYCZY
    if rssi < -85: s += 1                             # ledwo złapana
    return s

def rarity_from(s):
    return 3 if s >= 8 else 2 if s >= 5 else 1 if s >= 2 else 0

def type_for(ssid, is_open, hidden):
    if hidden: return "Duch"
    low = ssid.lower()
    if any(w in low for w in PHONE): return "Elektryk"
    if is_open: return "Wróżka"
    if any(w in low for w in BORING): return "Normalny"
    return TYPES[fnv(ssid + "t") % 8]

def make_name(ssid, r, hidden):
    h = fnv(ssid)
    root = ""
    if not hidden:
        for c in ssid:
            if "a" <= c.lower() <= "z":
                root += c.lower()
                if len(root) >= 6: break
            elif len(root) >= 3:
                break                                 # koniec pierwszego słowa
    if len(root) < 3:
        root = SYL[h % 12] + SYL[(h >> 4) % 12]
    suf = SUF[r][(h >> 8) % 6]
    if root[-1] in "aeiouy" and suf[0] in "aeiouy":
        root = root[:-1]                              # maszta+orax -> masztorax
    n = root + suf
    return n[0].upper() + n[1:]

# ---------- zapis ----------
# wpis: [id, ssid, imię, typ, rzadkość, najlepszy_rssi, ile_razy]
dex = []
index = {}

def load_dex():
    global dex, index
    try:
        with open(DEX_FILE) as f:
            dex = json.load(f)
    except Exception:
        dex = []
    index = {m[1]: m for m in dex}

def save_dex():
    with open(DEX_FILE, "w") as f:
        json.dump(dex, f)

# ---------- Bluetooth (Nordic UART) ----------
_UART = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_TX   = (bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ)
_RX   = (bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), bluetooth.FLAG_WRITE | bluetooth.FLAG_WRITE_NO_RESPONSE)

ble = bluetooth.BLE()
ble.active(True)
try:
    ble.config(mtu=247)
except Exception:
    pass
((tx_h, rx_h),) = ble.gatts_register_services(((_UART, (_TX, _RX)),))
ble.gatts_set_buffer(rx_h, 128, True)

conn = None
chunk = 20            # ile bajtów na raz (rośnie po negocjacji MTU)
want_list = False

def advertise():
    n = BLE_NAME.encode()
    ble.gap_advertise(200_000, b"\x02\x01\x06" + bytes((len(n) + 1, 0x09)) + n)

def irq(event, data):
    global conn, chunk, want_list
    if event == 1:                       # połączono
        conn = data[0]
        chunk = 20
    elif event == 2:                     # rozłączono
        conn = None
        advertise()
    elif event == 3:                     # telefon coś napisał
        if b"LIST" in ble.gatts_read(rx_h):
            want_list = True
    elif event == 21:                    # MTU wynegocjowane
        chunk = max(20, data[1] - 3)

ble.irq(irq)
advertise()

def ble_send(line):
    if conn is None:
        return
    data = line.encode()
    for i in range(0, len(data), chunk):
        part = data[i:i + chunk]
        for _ in range(20):              # bufor pełny -> chwila i ponów
            try:
                ble.gatts_notify(conn, tx_h, part)
                break
            except OSError:
                time.sleep_ms(15)
        else:
            return
        time.sleep_ms(4)

def clean(s):
    return s.replace("\t", " ").replace("\n", " ")

def line_for(kind, m):
    # kind \t id \t imię \t typ \t rzadkość \t rssi \t ile \t ssid
    return "%s\t%d\t%s\t%s\t%d\t%d\t%d\t%s\n" % (kind, m[0], m[2], m[3], m[4], m[5], m[6], clean(m[1]))

def send_list():
    ble_send("B\n")
    for m in dex:
        if conn is None:
            return
        ble_send(line_for("E", m))
    ble_send("D\n")

# ---------- skan ----------
sta = network.WLAN(network.STA_IF)

def scan():
    sta.active(True)
    try:
        nets = sta.scan()
    except Exception as e:
        print("Skan padł:", e)
        nets = []
    sta.active(False)                    # oszczędzanie baterii
    changed = False
    for raw_ssid, bssid, ch, rssi, sec, hid in nets:
        try:
            ssid = raw_ssid.decode()
        except Exception:
            ssid = ""
        hidden = len(ssid) == 0
        if hidden:
            ssid = "#ukryta " + ":".join("%02x" % b for b in bssid)
        m = index.get(ssid)
        if m:
            m[6] += 1
            if rssi > m[5]: m[5] = rssi
            changed = True
        elif len(dex) < MAX_ENTRIES:
            r = rarity_from(epic_score(ssid, hidden, rssi))
            m = [len(dex) + 1, ssid, make_name(ssid, r, hidden),
                 type_for(ssid, sec == 0, hidden), r, rssi, 1]
            dex.append(m)
            index[ssid] = m
            print("ZŁAPANO! #%03d %s [%s, %s] <- \"%s\"" % (m[0], m[2], m[3], RARITY_NAME[r], ssid))
            ble_send(line_for("N", m))   # powiadomienie na żywo w apce
            changed = True
    if changed:
        save_dex()
    gc.collect()

# ---------- główna pętla ----------
machine.freq(80_000_000)                 # mniej prądu
load_dex()
print("Pokedex: %d sieci w kolekcji" % len(dex))
scan()
last_scan = time.time()

while True:
    if want_list:
        want_list = False
        send_list()
    if time.time() - last_scan > SCAN_EVERY_S:
        scan()
        last_scan = time.time()
    time.sleep_ms(50)
