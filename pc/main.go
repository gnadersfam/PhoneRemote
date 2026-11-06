package main

// Phone Remote - the PC side.
//
// It sits quietly and waits for messages from the phone app over the home
// Wi-Fi. It does no work at all until the phone asks for something.

import (
	"crypto/rand"
	"crypto/subtle"
	_ "embed"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var version = "dev"

//go:embed ui.html
var uiHTML []byte

const (
	discoveryPort = 47800
	firstPort     = 47801
	pcName        = "My PC"
)

type config struct {
	Token     string `json:"token"`
	Setup     bool   `json:"setup"`
	ScreenPin string `json:"screenPin"`
}

type app struct {
	mu       sync.Mutex
	cfg      config
	cfgPath  string
	code     string
	badTries int
	lastSeen time.Time
	port     int

	// screen view: phones that typed the screen PIN, and PIN guessing limits
	views        map[string]time.Time
	pinBad       int
	pinLockUntil time.Time
	watching     int32 // how many screen views are open right now

	// where the phone said it can be reached (for Find my phone)
	phoneHost string
	phonePort int
	phoneSeen time.Time
}

func randomDigits() string {
	var b [2]byte
	rand.Read(b[:])
	n := int(binary.BigEndian.Uint16(b[:])) % 10000
	return fmt.Sprintf("%04d", n)
}

func randomToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (a *app) load() {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = os.TempDir()
	}
	dir = filepath.Join(dir, "PhoneRemote")
	os.MkdirAll(dir, 0o755)
	a.cfgPath = filepath.Join(dir, "config.json")
	data, err := os.ReadFile(a.cfgPath)
	if err == nil {
		json.Unmarshal(data, &a.cfg)
	}
	a.code = randomDigits()
	a.views = map[string]time.Time{}
	if len(a.cfg.ScreenPin) != 4 {
		a.cfg.ScreenPin = randomDigits()
		a.save()
	}
}

func (a *app) save() {
	data, err := json.Marshal(a.cfg)
	if err == nil {
		os.WriteFile(a.cfgPath, data, 0o600)
	}
}

func setupLog(dir string) {
	path := filepath.Join(dir, "log.txt")
	if st, err := os.Stat(path); err == nil && st.Size() > 200*1024 {
		os.Remove(path)
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err == nil {
		log.SetOutput(f)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func isLAN(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}

func isLocal(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return false
	}
	h := r.Host
	if i := strings.LastIndex(h, ":"); i >= 0 {
		h = h[:i]
	}
	return h == "127.0.0.1" || h == "localhost"
}

func (a *app) lanOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !isLAN(r) {
			writeJSON(w, 403, map[string]any{"error": "forbidden"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

// Only the phone that paired (and so knows the secret token) gets through.
func (a *app) authed(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		token := a.cfg.Token
		a.mu.Unlock()
		got := r.Header.Get("X-Token")
		if token == "" || subtle.ConstantTimeCompare([]byte(got), []byte(token)) != 1 {
			writeJSON(w, 401, map[string]any{"error": "unauthorized"})
			return
		}
		a.mu.Lock()
		a.lastSeen = time.Now()
		a.mu.Unlock()
		next(w, r)
	}
}

// Only the window on this PC itself may use these.
func (a *app) local(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !isLocal(r) {
			writeJSON(w, 403, map[string]any{"error": "forbidden"})
			return
		}
		if r.Method == http.MethodPost && r.Header.Get("X-PR") != "1" {
			writeJSON(w, 403, map[string]any{"error": "forbidden"})
			return
		}
		next(w, r)
	}
}

func (a *app) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/pair", a.handlePair)
	mux.HandleFunc("/ping", a.authed(a.handlePing))
	mux.HandleFunc("/state", a.authed(a.handleState))
	mux.HandleFunc("/volume", a.authed(a.handleVolume))
	mux.HandleFunc("/mute", a.authed(a.handleMute))
	mux.HandleFunc("/media", a.authed(a.handleMedia))
	mux.HandleFunc("/power", a.authed(a.handlePower))
	mux.HandleFunc("/type", a.authed(a.handleType))
	mux.HandleFunc("/key", a.authed(a.handleKey))
	mux.HandleFunc("/mouse/move", a.authed(a.handleMouseMove))
	mux.HandleFunc("/mouse/click", a.authed(a.handleMouseClick))
	mux.HandleFunc("/mouse/scroll", a.authed(a.handleMouseScroll))
	mux.HandleFunc("/mouse/abs", a.authed(a.handleMouseAbs))
	mux.HandleFunc("/mouse/button", a.authed(a.handleMouseButton))
	mux.HandleFunc("/screen/unlock", a.authed(a.handleScreenUnlock))
	mux.HandleFunc("/screen/shot", a.viewAuthed(a.handleScreenShot))
	mux.HandleFunc("/screen/stream", a.viewAuthed(a.handleScreenStream))
	mux.HandleFunc("/audio/stream", a.viewAuthed(a.handleAudioStream))
	mux.HandleFunc("/register", a.authed(a.handleRegister))
	mux.HandleFunc("/ui", a.local(a.handleUI))
	mux.HandleFunc("/ui/find", a.local(a.handleUIFind))
	mux.HandleFunc("/ui/status", a.local(a.handleUIStatus))
	mux.HandleFunc("/ui/quit", a.local(a.handleUIQuit))
	mux.HandleFunc("/ui/autostart", a.local(a.handleUIAutostart))
	mux.HandleFunc("/ui/pin", a.local(a.handleUIPinNew))
	return a.lanOnly(mux)
}

func (a *app) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	r.ParseForm()
	got := strings.TrimSpace(r.Form.Get("code"))
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(got) == 4 && subtle.ConstantTimeCompare([]byte(got), []byte(a.code)) == 1 {
		a.cfg.Token = randomToken()
		a.save()
		a.code = randomDigits()
		a.badTries = 0
		a.lastSeen = time.Now()
		log.Printf("phone paired")
		writeJSON(w, 200, map[string]any{"token": a.cfg.Token, "name": pcName})
		return
	}
	a.badTries++
	if a.badTries >= 5 {
		a.code = randomDigits()
		a.badTries = 0
		log.Printf("too many wrong codes, new code made")
	}
	writeJSON(w, 403, map[string]any{"error": "wrong code"})
}

func (a *app) handlePing(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"ok": true, "name": pcName})
}

func (a *app) stateMap() map[string]any {
	vol, muted, ok := osVolumeGet()
	m := map[string]any{"name": pcName, "now": osNowPlaying(), "volumeOk": ok}
	if ok {
		m["volume"] = vol
		m["muted"] = muted
	} else {
		m["volume"] = 0
		m["muted"] = false
	}
	return m
}

func (a *app) handleState(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, a.stateMap())
}

func (a *app) handleVolume(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	lvl, err := strconv.Atoi(r.URL.Query().Get("level"))
	if err != nil || lvl < 0 || lvl > 100 {
		writeJSON(w, 400, map[string]any{"error": "bad level"})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": osVolumeSet(lvl)})
}

func (a *app) handleMute(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	muted, ok := osMuteToggle()
	writeJSON(w, 200, map[string]any{"ok": ok, "muted": muted})
}

func (a *app) handleMedia(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	act := r.URL.Query().Get("action")
	if act != "playpause" && act != "next" && act != "prev" {
		writeJSON(w, 400, map[string]any{"error": "bad action"})
		return
	}
	osMediaKey(act)
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *app) handlePower(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	act := r.URL.Query().Get("action")
	if act != "sleep" && act != "restart" && act != "shutdown" {
		writeJSON(w, 400, map[string]any{"error": "bad action"})
		return
	}
	log.Printf("power: %s", act)
	writeJSON(w, 200, map[string]any{"ok": true})
	go func() {
		time.Sleep(700 * time.Millisecond)
		osPower(act)
	}()
}

// ---- keyboard and mouse

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func queryInt(r *http.Request, name string, def int) int {
	v, err := strconv.Atoi(r.URL.Query().Get(name))
	if err != nil {
		return def
	}
	return v
}

func (a *app) handleType(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 32*1024)
	if err := r.ParseForm(); err != nil {
		writeJSON(w, 400, map[string]any{"error": "bad request"})
		return
	}
	text := r.Form.Get("text")
	if len([]rune(text)) > 4000 {
		writeJSON(w, 400, map[string]any{"error": "too long"})
		return
	}
	osTypeText(text)
	writeJSON(w, 200, map[string]any{"ok": true})
}

var allowedKeys = map[string]bool{
	"enter": true, "backspace": true, "tab": true, "esc": true, "delete": true,
	"left": true, "right": true, "up": true, "down": true, "home": true, "end": true, "space": true,
}

func (a *app) handleKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	name := r.URL.Query().Get("name")
	if !allowedKeys[name] {
		writeJSON(w, 400, map[string]any{"error": "bad key"})
		return
	}
	count := clampInt(queryInt(r, "count", 1), 1, 60)
	osKey(name, count)
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *app) handleMouseMove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	dx := clampInt(queryInt(r, "dx", 0), -600, 600)
	dy := clampInt(queryInt(r, "dy", 0), -600, 600)
	osMouseMove(dx, dy)
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *app) handleMouseClick(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	btn := r.URL.Query().Get("button")
	if btn != "left" && btn != "right" {
		writeJSON(w, 400, map[string]any{"error": "bad button"})
		return
	}
	osMouseClick(btn)
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *app) handleMouseScroll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	// 120 is one notch of a mouse wheel
	dy := clampInt(queryInt(r, "dy", 0), -1200, 1200)
	osMouseScroll(dy)
	writeJSON(w, 200, map[string]any{"ok": true})
}

// Moves the pointer to a spot on the screen. x and y run from 0 to 10000
// (left to right, top to bottom), so they work whatever the picture size.
// Add click=left or click=right to click there as well.
func (a *app) handleMouseAbs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	x := clampInt(queryInt(r, "x", 0), 0, 10000)
	y := clampInt(queryInt(r, "y", 0), 0, 10000)
	click := r.URL.Query().Get("click")
	if click != "" && click != "left" && click != "right" {
		writeJSON(w, 400, map[string]any{"error": "bad button"})
		return
	}
	osMouseSet(float64(x)/10000, float64(y)/10000)
	if click != "" {
		osMouseClick(click)
	}
	writeJSON(w, 200, map[string]any{"ok": true})
}

// Holds a mouse button down or lets it go (for dragging things).
func (a *app) handleMouseButton(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	btn := r.URL.Query().Get("button")
	act := r.URL.Query().Get("action")
	if (btn != "left" && btn != "right") || (act != "down" && act != "up") {
		writeJSON(w, 400, map[string]any{"error": "bad request"})
		return
	}
	osMouseButton(btn, act == "down")
	writeJSON(w, 200, map[string]any{"ok": true})
}

// ---- Find my phone

// The phone tells us where it is now and then, so the PC knows where to knock.
func (a *app) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	port := queryInt(r, "port", 0)
	if port < 1 || port > 65535 {
		writeJSON(w, 400, map[string]any{"error": "bad port"})
		return
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		writeJSON(w, 400, map[string]any{"error": "bad address"})
		return
	}
	a.mu.Lock()
	a.phoneHost = host
	a.phonePort = port
	a.phoneSeen = time.Now()
	a.mu.Unlock()
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *app) handleUIFind(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	stop := r.URL.Query().Get("stop") == "1"
	a.mu.Lock()
	host, port, token := a.phoneHost, a.phonePort, a.cfg.Token
	a.mu.Unlock()
	if host == "" || token == "" {
		writeJSON(w, 200, map[string]any{"ok": false, "message": "Your phone hasn't checked in yet. Open the app on your phone while it is on your Wi-Fi."})
		return
	}
	path := "/ring"
	if stop {
		path = "/stop"
	}
	req, err := http.NewRequest(http.MethodPost, "http://"+net.JoinHostPort(host, strconv.Itoa(port))+path, nil)
	if err != nil {
		writeJSON(w, 200, map[string]any{"ok": false, "message": "Something went wrong."})
		return
	}
	req.Header.Set("X-Token", token)
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("find my phone: %v", err)
		writeJSON(w, 200, map[string]any{"ok": false, "message": "Couldn't reach your phone. Is it on your home Wi-Fi?"})
		return
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		writeJSON(w, 200, map[string]any{"ok": false, "message": "Your phone didn't accept that. Try opening the app on it."})
		return
	}
	log.Printf("find my phone: %s", path)
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *app) handleUI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'")
	w.Write(uiHTML)
}

func lanIP() string {
	c, err := net.Dial("udp4", "8.8.8.8:80")
	if err == nil {
		defer c.Close()
		if ua, ok := c.LocalAddr().(*net.UDPAddr); ok {
			return ua.IP.String()
		}
	}
	addrs, _ := net.InterfaceAddrs()
	for _, ad := range addrs {
		if ipn, ok := ad.(*net.IPNet); ok && ipn.IP.To4() != nil && ipn.IP.IsPrivate() {
			return ipn.IP.String()
		}
	}
	return ""
}

func (a *app) handleUIStatus(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	code := a.code
	paired := a.cfg.Token != ""
	connected := !a.lastSeen.IsZero() && time.Since(a.lastSeen) < 20*time.Second
	port := a.port
	phoneKnown := a.phoneHost != "" && time.Since(a.phoneSeen) < 10*time.Minute
	pin := a.cfg.ScreenPin
	a.mu.Unlock()
	writeJSON(w, 200, map[string]any{
		"pin":        pin,
		"watching":   atomic.LoadInt32(&a.watching) > 0,
		"phoneKnown": phoneKnown,
		"name":       pcName,
		"ip":         lanIP(),
		"port":       port,
		"code":       code,
		"paired":     paired,
		"connected":  connected,
		"autostart":  osAutostartGet(),
		"version":    version,
	})
}

func (a *app) handleUIQuit(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
	go func() {
		time.Sleep(300 * time.Millisecond)
		quitApp()
	}()
}

func (a *app) handleUIAutostart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	osAutostartSet(r.URL.Query().Get("on") == "1")
	writeJSON(w, 200, map[string]any{"ok": true, "autostart": osAutostartGet()})
}

// The phone shouts "PHONEREMOTE?" across the Wi-Fi; we answer with our port.
func (a *app) discoveryLoop() {
	pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
		log.Printf("discovery could not start: %v", err)
		return
	}
	defer pc.Close()
	buf := make([]byte, 256)
	for {
		n, addr, err := pc.ReadFrom(buf)
		if err != nil {
			time.Sleep(time.Second)
			continue
		}
		if strings.TrimSpace(string(buf[:n])) == "PHONEREMOTE?" {
			reply := fmt.Sprintf("PHONEREMOTE!%d|%s", a.port, pcName)
			pc.WriteTo([]byte(reply), addr)
		}
	}
}

func quitApp() {
	osCleanup()
	os.Exit(0)
}

func main() {
	background := false
	for _, arg := range os.Args[1:] {
		if arg == "-background" {
			background = true
		}
	}
	if osAlreadyRunning() {
		if !background {
			osOpenUI(fmt.Sprintf("http://127.0.0.1:%d/ui", firstPort))
		}
		return
	}

	a := &app{}
	a.load()
	setupLog(filepath.Dir(a.cfgPath))
	log.Printf("Phone Remote %s starting", version)

	var ln net.Listener
	for p := firstPort; p < firstPort+10; p++ {
		l, err := net.Listen("tcp", fmt.Sprintf(":%d", p))
		if err == nil {
			ln = l
			a.port = p
			break
		}
	}
	if ln == nil {
		log.Printf("no free port, giving up")
		return
	}
	srv := &http.Server{
		Handler:           a.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	go func() { log.Printf("server stopped: %v", srv.Serve(ln)) }()
	go a.discoveryLoop()

	if !a.cfg.Setup {
		a.cfg.Setup = true
		a.save()
		osAutostartSet(true)
	}

	openUI := func() { osOpenUI(fmt.Sprintf("http://127.0.0.1:%d/ui", a.port)) }
	if !background {
		go func() {
			time.Sleep(600 * time.Millisecond)
			openUI()
		}()
	}
	osRunTray(openUI, quitApp)
}
