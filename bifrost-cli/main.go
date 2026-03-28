package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/charmbracelet/lipgloss"
)

// ── Styles ─────────────────────────────────────────────────────────────────

var (
	headerStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("205")).
			Bold(true).
			Padding(0, 1).
			Background(lipgloss.Color("236"))

	footerStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("240")).
			Padding(0, 1).
			Background(lipgloss.Color("236"))

	titleStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("205")).
			Bold(true).
			PaddingLeft(2)

	subtitleStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("240")).
			PaddingLeft(2)

	selectedItemStyle = lipgloss.NewStyle().
				Foreground(lipgloss.Color("212")).
				Bold(true)

	normalItemStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("252"))

	paneHeaderStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("14")).
			Bold(true)

	selectedRowStyle = lipgloss.NewStyle().
				Foreground(lipgloss.Color("11")).
				Bold(true).
				Background(lipgloss.Color("236"))

	keyStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("39")).
			Bold(true)

	valStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("252"))

	dimStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("240"))

	errStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("196")).
			Bold(true)

	inputStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("255")).
			Background(lipgloss.Color("238")).
			Padding(0, 1)
)

// ── Types ──────────────────────────────────────────────────────────────────

type state int

const (
	dirSelector state = iota
	traceViewer
	traceDetails
)

// TraceRecord represents a single trace record
type TraceRecord struct {
	SchemaVersion int         `json:"schemaVersion"`
	TraceID       string      `json:"traceId"`
	SessionID     string      `json:"sessionId"`
	Sequence      int         `json:"sequence"`
	Timestamp     float64     `json:"timestamp"`
	RecordType    string      `json:"recordType"`
	FrameID       *string     `json:"frameId"`
	ParentFrameID *string     `json:"parentFrameId"`
	FrameType     *string     `json:"frameType"`
	Route         *string     `json:"route"`
	ThreadName    string      `json:"threadName"`
	Metadata      interface{} `json:"metadata"`
	Data          interface{} `json:"data"`
	Duration      float64     `json:"-"` // Calculated duration in milliseconds
}

// TraceFile represents a trace file with summary metadata
type TraceFile struct {
	FileName    string
	FilePath    string
	SessionID   string
	TraceID     string
	SkillName   string
	RecordCount int
	Duration    float64
	Errored     bool
	StartTime   time.Time
}

// Model holds the application state
type model struct {
	state        state
	tracesDir    string
	dirInput     string
	traceFiles   []TraceFile
	traceCursor  int
	traceRecords []TraceRecord
	frameCursor  int
	detailOffset int
	width        int
	height       int
	errMsg       string
}

// initialModel returns the starting model; traces load immediately on Init
func initialModel() model {
	return model{
		state:     traceViewer,
		tracesDir: "./traces",
	}
}

// ── Tea lifecycle ──────────────────────────────────────────────────────────

func (m model) Init() tea.Cmd {
	return loadTracesCmd(m.tracesDir)
}

// ── Messages ───────────────────────────────────────────────────────────────

type tracesLoadedMsg struct {
	traces []TraceFile
	dir    string
	err    error
}

type traceDetailsLoadedMsg struct {
	records []TraceRecord
	err     error
}

// ── Update ─────────────────────────────────────────────────────────────────

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height

	case tracesLoadedMsg:
		if msg.err != nil {
			// Directory unreadable — drop into selector
			m.state = dirSelector
			m.dirInput = m.tracesDir
			m.errMsg = msg.err.Error()
			return m, nil
		}
		m.traceFiles = msg.traces
		m.tracesDir = msg.dir
		m.traceCursor = 0
		m.state = traceViewer
		m.errMsg = ""
		return m, nil

	case traceDetailsLoadedMsg:
		if msg.err != nil {
			m.errMsg = msg.err.Error()
			return m, nil
		}
		m.traceRecords = msg.records
		m.frameCursor = 0
		m.detailOffset = 0
		m.state = traceDetails
		return m, nil

	case tea.KeyPressMsg:
		return m.handleKey(msg.String())
	}

	return m, nil
}

func (m model) handleKey(key string) (model, tea.Cmd) {
	switch m.state {
	case dirSelector:
		return m.handleDirKey(key)
	case traceViewer:
		return m.handleViewerKey(key)
	case traceDetails:
		return m.handleDetailsKey(key)
	}
	return m, nil
}

func (m model) handleDirKey(key string) (model, tea.Cmd) {
	switch key {
	case "enter":
		dir := strings.TrimSpace(m.dirInput)
		if dir == "" {
			dir = "./traces"
		}
		m.tracesDir = dir
		m.errMsg = ""
		return m, loadTracesCmd(dir)
	case "esc":
		if len(m.traceFiles) > 0 {
			m.state = traceViewer
		}
		return m, nil
	case "ctrl+c", "q":
		return m, tea.Quit
	case "backspace":
		if len(m.dirInput) > 0 {
			m.dirInput = m.dirInput[:len(m.dirInput)-1]
		}
	default:
		if len(key) == 1 {
			m.dirInput += key
		}
	}
	return m, nil
}

func (m model) handleViewerKey(key string) (model, tea.Cmd) {
	switch key {
	case "q", "ctrl+c":
		return m, tea.Quit
	case "d":
		m.state = dirSelector
		m.dirInput = m.tracesDir
	case "up", "k":
		if m.traceCursor > 0 {
			m.traceCursor--
		}
	case "down", "j":
		if m.traceCursor < len(m.traceFiles)-1 {
			m.traceCursor++
		}
	case "enter", " ":
		if len(m.traceFiles) > 0 {
			return m, loadDetailsCmd(m.traceFiles[m.traceCursor].FilePath)
		}
	}
	return m, nil
}

func (m model) handleDetailsKey(key string) (model, tea.Cmd) {
	detailVisible := m.detailPaneHeight() - 4
	if detailVisible < 4 {
		detailVisible = 4
	}
	switch key {
	case "q", "ctrl+c":
		return m, tea.Quit
	case "esc":
		m.state = traceViewer
	case "up", "k":
		if m.frameCursor > 0 {
			m.frameCursor--
			m.detailOffset = 0
		}
	case "down", "j":
		if m.frameCursor < len(m.traceRecords)-1 {
			m.frameCursor++
			m.detailOffset = 0
		}
	case "ctrl+d", "pgdown":
		m.detailOffset += detailVisible / 2
	case "ctrl+u", "pgup":
		m.detailOffset -= detailVisible / 2
		if m.detailOffset < 0 {
			m.detailOffset = 0
		}
	}
	return m, nil
}

// ── View ───────────────────────────────────────────────────────────────────

func (m model) View() tea.View {
	header := headerStyle.Render("  Bifrost CLI ")

	var body string
	switch m.state {
	case dirSelector:
		body = m.viewDirSelector()
	case traceViewer:
		body = m.viewTraceList()
	case traceDetails:
		body = m.viewTraceDetails()
	}

	footer := m.viewFooter()
	full := header + "\n" + body + "\n" + footer

	v := tea.NewView(full)
	v.AltScreen = true
	v.WindowTitle = "Bifrost CLI"
	return v
}

func (m model) viewFooter() string {
	var hints string
	switch m.state {
	case dirSelector:
		hints = "Enter: open  Esc: cancel  Backspace: delete  q: quit"
	case traceViewer:
		hints = "↑↓ / jk: navigate  Enter: open  d: change dir  q: quit"
	case traceDetails:
		hints = "↑↓ / jk: records  Ctrl+D/U: scroll detail  Esc: back  q: quit"
	}
	if m.errMsg != "" {
		return errStyle.Render("  Error: "+m.errMsg) + "\n" + footerStyle.Render(hints)
	}
	return footerStyle.Render(hints)
}

// ── Dir Selector ───────────────────────────────────────────────────────────

func (m model) viewDirSelector() string {
	var sb strings.Builder
	sb.WriteString("\n")
	sb.WriteString(titleStyle.Render("Select Traces Directory") + "\n\n")
	sb.WriteString(subtitleStyle.Render("Current directory: "+m.tracesDir) + "\n\n")
	sb.WriteString(subtitleStyle.Render("Enter path:") + "\n")
	sb.WriteString("  " + inputStyle.Render(m.dirInput+"█") + "\n")
	return sb.String()
}

// ── Trace List ─────────────────────────────────────────────────────────────

func (m model) viewTraceList() string {
	var sb strings.Builder
	sb.WriteString("\n")
	sb.WriteString(titleStyle.Render("Execution Traces") + "\n")
	sb.WriteString(subtitleStyle.Render("Dir: "+m.tracesDir) + "\n\n")

	if len(m.traceFiles) == 0 {
		sb.WriteString(subtitleStyle.Render("  No trace files found") + "\n")
		return sb.String()
	}

	for i, tf := range m.traceFiles {
		cursor := " "
		if m.traceCursor == i {
			cursor = "→"
		}
		style := normalItemStyle
		if m.traceCursor == i {
			style = selectedItemStyle
		}

		// Status badge
		statusBadge := "  ✓"
		if tf.Errored {
			statusBadge = errStyle.Render("  ✗")
		}

		// Skill / session label
		label := tf.SkillName
		if label == "" {
			label = shortID(tf.SessionID)
		}

		// Timestamp
		ts := ""
		if !tf.StartTime.IsZero() {
			ts = tf.StartTime.Format("01-02 15:04:05")
		}

		// Duration
		dur := formatDuration(tf.Duration)

		line := fmt.Sprintf("%-26s  %s  %-7s  %3d records",
			label, ts, dur, tf.RecordCount)
		sb.WriteString(fmt.Sprintf(" %s %s%s\n", cursor, style.Render(line), statusBadge))
	}

	return sb.String()
}

// ── Trace Details (Wireshark-style) ───────────────────────────────────────

func (m model) recordListHeight() int {
	h := m.height / 3
	if h < 7 {
		h = 7
	}
	if h > 18 {
		h = 18
	}
	return h
}

func (m model) detailPaneHeight() int {
	h := m.height - m.recordListHeight() - 8
	if h < 8 {
		h = 8
	}
	return h
}

func (m model) viewTraceDetails() string {
	records := m.traceRecords
	listH := m.recordListHeight()
	detailH := m.detailPaneHeight()
	paneW := m.width - 4
	if paneW < 60 {
		paneW = 60
	}

	// ── Top pane: record list ──────────────────────────────────────────────
	topContent := paneHeaderStyle.Render(fmt.Sprintf(" RECORDS (%d)", len(records))) + "\n\n"

	visible := listH - 4
	if visible < 1 {
		visible = 1
	}
	start := 0
	if m.frameCursor >= visible {
		start = m.frameCursor - visible + 1
	}
	end := start + visible
	if end > len(records) {
		end = len(records)
	}

	// Track trace start for Δtime
	var traceStart float64
	if len(records) > 0 {
		traceStart = records[0].Timestamp
	}

	// Column widths (content only, no ANSI)
	const seqW = 5
	const dtimeW = 9
	const typeW = 12

	for i := start; i < end; i++ {
		r := records[i]
		isSelected := m.frameCursor == i

		// [seq]
		seqStr := fmt.Sprintf("%-*s", seqW, fmt.Sprintf("[%d]", r.Sequence))

		// +Δtime
		deltaMs := (r.Timestamp - traceStart) * 1000
		dtimeStr := fmt.Sprintf("%-*s", dtimeW, fmt.Sprintf("+%.0fms", deltaMs))

		// Type abbreviation (colorized only when not selected)
		abbrev, color := typeAbbrevColor(r.RecordType)
		paddedAbbrev := fmt.Sprintf("%-*s", typeW, abbrev)
		var typeStr string
		if isSelected {
			typeStr = paddedAbbrev
		} else {
			typeStr = lipgloss.NewStyle().Foreground(lipgloss.Color(color)).Render(paddedAbbrev)
		}

		// Smart info
		infoMaxLen := paneW - seqW - dtimeW - typeW - 8
		if infoMaxLen < 10 {
			infoMaxLen = 10
		}
		info := truncate(smartInfo(r), infoMaxLen)

		row := seqStr + " " + dtimeStr + " " + typeStr + " " + info
		if isSelected {
			topContent += "→" + selectedRowStyle.Render(row) + "\n"
		} else {
			topContent += " " + row + "\n"
		}
	}

	topPane := lipgloss.NewStyle().
		Border(lipgloss.RoundedBorder()).
		Padding(0, 1).
		Width(paneW).
		Height(listH).
		Render(topContent)

	// ── Bottom pane: record detail ─────────────────────────────────────────
	var detailContent string
	if m.frameCursor < len(records) {
		detailContent = m.renderDetailPane(records[m.frameCursor], detailH)
	}

	bottomPane := lipgloss.NewStyle().
		Border(lipgloss.RoundedBorder()).
		Padding(0, 1).
		Width(paneW).
		Height(detailH).
		Render(detailContent)

	return "\n" + topPane + "\n" + bottomPane + "\n"
}

func (m model) renderDetailPane(r TraceRecord, maxHeight int) string {
	lines := buildDetailLines(r)

	offset := m.detailOffset
	if offset >= len(lines) {
		offset = 0
	}
	end := offset + maxHeight - 3
	if end > len(lines) {
		end = len(lines)
	}

	title := paneHeaderStyle.Render(fmt.Sprintf(" ► %s  seq:%d", r.RecordType, r.Sequence))

	scrollHint := ""
	if len(lines) > maxHeight-3 {
		scrollHint = dimStyle.Render(fmt.Sprintf("  (%d-%d of %d lines  Ctrl+D/U to scroll)", offset+1, end, len(lines)))
	}

	return title + "\n" + strings.Join(lines[offset:end], "\n") + scrollHint
}

func buildDetailLines(r TraceRecord) []string {
	var lines []string

	// Frame duration (when available)
	if r.Duration > 0 {
		lines = append(lines,
			fmt.Sprintf("  %s  %s",
				keyStyle.Render(fmt.Sprintf("%-22s", "duration")),
				valStyle.Render(formatDuration(r.Duration))))
		lines = append(lines, "")
	}

	// Metadata section
	if r.Metadata != nil {
		lines = append(lines, keyStyle.Render("  METADATA"))
		if meta, ok := r.Metadata.(map[string]interface{}); ok {
			for _, k := range sortedKeys(meta) {
				lines = append(lines,
					fmt.Sprintf("    %s  %s",
						keyStyle.Render(fmt.Sprintf("%-22s", k)),
						valStyle.Render(fmt.Sprintf("%v", meta[k]))))
			}
		}
		lines = append(lines, "")
	}

	// Data section
	if r.Data != nil {
		lines = append(lines, keyStyle.Render("  DATA"))
		dataBytes, err := json.MarshalIndent(r.Data, "    ", "  ")
		if err == nil {
			for _, dl := range strings.Split(string(dataBytes), "\n") {
				lines = append(lines, valStyle.Render(dl))
			}
		}
	} else {
		lines = append(lines, dimStyle.Render("  (no data)"))
	}

	return lines
}

// ── Type abbreviation + color ──────────────────────────────────────────────

func typeAbbrevColor(rt string) (string, string) {
	switch rt {
	// Infrastructure — dim
	case "TRACE_STARTED":
		return "STARTED", "240"
	case "TRACE_CAPTURE_POLICY_RECORDED":
		return "POLICY", "240"
	case "TRACE_COMPLETED":
		return "COMPLETED", "240"
	// Frame lifecycle — blue
	case "FRAME_OPENED":
		return "FRAME\u25b6", "39"
	case "FRAME_CLOSED":
		return "FRAME\u25a0", "39"
	case "FRAME_METADATA_RECORDED":
		return "FRAME_META", "39"
	// Model calls — green
	case "MODEL_REQUEST_PREPARED":
		return "REQ_PREP", "82"
	case "MODEL_REQUEST_SENT":
		return "REQ_SENT", "82"
	case "MODEL_RESPONSE_RECEIVED":
		return "RESPONSE", "118"
	// Advisors — yellow
	case "ADVISOR_REQUEST_MUTATION_RECORDED":
		return "ADV_REQ", "220"
	case "ADVISOR_RESPONSE_MUTATION_RECORDED":
		return "ADV_RESP", "220"
	// Validation outcomes — magenta
	case "STRUCTURED_OUTPUT_RECORDED":
		return "SCHEMA", "135"
	case "LINTER_RECORDED":
		return "LINTER", "135"
	// Planning — cyan
	case "PLAN_CREATED":
		return "PLAN_NEW", "51"
	case "PLAN_UPDATED":
		return "PLAN_UPD", "51"
	// Tools — orange
	case "TOOL_CALL_REQUESTED":
		return "TOOL_REQ", "214"
	case "TOOL_CALL_STARTED":
		return "TOOL_START", "214"
	case "TOOL_CALL_COMPLETED":
		return "TOOL_DONE", "214"
	case "TOOL_CALL_FAILED":
		return "TOOL_FAIL", "196"
	// Errors — red
	case "ERROR_RECORDED":
		return "ERROR", "196"
	}
	return rt, "252"
}

// ── Smart info column ──────────────────────────────────────────────────────

func smartInfo(r TraceRecord) string {
	meta, _ := r.Metadata.(map[string]interface{})
	data, _ := r.Data.(map[string]interface{})

	switch r.RecordType {
	case "TRACE_STARTED":
		return "session:" + shortID(r.SessionID)

	case "TRACE_CAPTURE_POLICY_RECORDED":
		if meta != nil {
			return strVal(meta["persistencePolicy"])
		}

	case "FRAME_OPENED":
		var parts []string
		if meta != nil {
			if ft := strVal(meta["frameType"]); ft != "" {
				parts = append(parts, ft)
			}
			if ot := strVal(meta["operationType"]); ot != "" {
				parts = append(parts, ot)
			}
		}
		if r.Route != nil {
			parts = append(parts, *r.Route)
		}
		return strings.Join(parts, " │ ")

	case "FRAME_CLOSED":
		d := formatDuration(r.Duration)
		if r.Route != nil {
			return *r.Route + "  " + d
		}
		return d

	case "MODEL_REQUEST_PREPARED", "MODEL_REQUEST_SENT":
		if meta != nil {
			info := strVal(meta["provider"]) + "/" + strVal(meta["providerModel"])
			if sn := strVal(meta["skillName"]); sn != "" {
				info += "  " + sn
			}
			if r.RecordType == "MODEL_REQUEST_SENT" && data != nil {
				if cnt, ok := data["toolCallbackCount"].(float64); ok && cnt > 0 {
					info += fmt.Sprintf("  tools:%d", int(cnt))
				}
			}
			return info
		}

	case "MODEL_RESPONSE_RECEIVED":
		if data != nil {
			if content := strVal(data["content"]); content != "" {
				return truncate(strings.ReplaceAll(content, "\n", " "), 80)
			}
		}

	case "ADVISOR_REQUEST_MUTATION_RECORDED", "ADVISOR_RESPONSE_MUTATION_RECORDED":
		if meta != nil {
			adv := strVal(meta["advisorName"])
			status := strVal(meta["status"])
			attempt := ""
			if a, ok := meta["attempt"].(float64); ok {
				attempt = fmt.Sprintf(" #%d", int(a))
			}
			info := adv + "  " + status + attempt
			if r.RecordType == "ADVISOR_RESPONSE_MUTATION_RECORDED" && data != nil {
				if cand := strVal(data["candidate"]); cand != "" {
					info += "  │ " + truncate(strings.ReplaceAll(cand, "\n", " "), 35)
				}
			}
			return info
		}

	case "STRUCTURED_OUTPUT_RECORDED":
		if data != nil {
			status := strVal(data["status"])
			if a, ok := data["attempt"].(float64); ok {
				maxR, _ := data["maxRetries"].(float64)
				return fmt.Sprintf("schema %s  attempt %d/%d", status, int(a), int(maxR)+1)
			}
			return "schema " + status
		}

	case "LINTER_RECORDED":
		if data != nil {
			return fmt.Sprintf("%s %s  \"%s\"",
				strVal(data["linterType"]),
				strVal(data["status"]),
				truncate(strVal(data["detail"]), 40))
		}

	case "PLAN_CREATED", "PLAN_UPDATED":
		if meta != nil {
			return "plan:" + strVal(meta["planId"])
		}

	case "TOOL_CALL_REQUESTED", "TOOL_CALL_STARTED", "TOOL_CALL_COMPLETED", "TOOL_CALL_FAILED":
		if meta != nil {
			return strVal(meta["toolName"])
		}

	case "ERROR_RECORDED":
		if data != nil {
			return truncate(fmt.Sprintf("%v", data), 70)
		}
		return "ERROR"

	case "TRACE_COMPLETED":
		if meta != nil {
			skill := strVal(meta["skillName"])
			if e, ok := meta["errored"].(bool); ok && e {
				return skill + "  ERRORED"
			}
			return skill + "  ok"
		}
	}
	return ""
}

// ── Loading ────────────────────────────────────────────────────────────────

func loadTracesCmd(dir string) tea.Cmd {
	return func() tea.Msg {
		traces, err := findTraceFiles(dir)
		return tracesLoadedMsg{traces: traces, dir: dir, err: err}
	}
}

func loadDetailsCmd(filePath string) tea.Cmd {
	return func() tea.Msg {
		records, err := loadTraceRecords(filePath)
		return traceDetailsLoadedMsg{records: records, err: err}
	}
}

func findTraceFiles(dir string) ([]TraceFile, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}

	var traces []TraceFile
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".execution-trace.ndjson") {
			continue
		}
		filePath := filepath.Join(dir, entry.Name())

		name := strings.TrimSuffix(entry.Name(), ".execution-trace.ndjson")
		parts := strings.Split(name, ".")
		sessionID, traceID := name, "unknown"
		if len(parts) >= 2 {
			sessionID, traceID = parts[0], parts[1]
		}

		tf := TraceFile{
			FileName:  entry.Name(),
			FilePath:  filePath,
			SessionID: sessionID,
			TraceID:   traceID,
		}
		scanTraceFileSummary(&tf)
		traces = append(traces, tf)
	}

	sort.Slice(traces, func(i, j int) bool {
		return traces[i].StartTime.After(traces[j].StartTime)
	})
	return traces, nil
}

// scanTraceFileSummary reads key fields from a trace file without loading all records.
func scanTraceFileSummary(tf *TraceFile) {
	file, err := os.Open(tf.FilePath)
	if err != nil {
		return
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	count := 0
	var firstTS, lastTS float64

	for decoder.More() {
		var r TraceRecord
		if err := decoder.Decode(&r); err != nil {
			break
		}
		count++
		if count == 1 {
			firstTS = r.Timestamp
			tf.StartTime = time.Unix(int64(r.Timestamp), 0)
		}
		lastTS = r.Timestamp

		if r.RecordType == "TRACE_COMPLETED" {
			if meta, ok := r.Metadata.(map[string]interface{}); ok {
				tf.SkillName = strVal(meta["skillName"])
				if e, ok := meta["errored"].(bool); ok {
					tf.Errored = e
				}
			}
		}
	}
	tf.RecordCount = count
	if firstTS > 0 && lastTS > firstTS {
		tf.Duration = (lastTS - firstTS) * 1000
	}
}

func loadTraceRecords(filePath string) ([]TraceRecord, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var records []TraceRecord
	decoder := json.NewDecoder(file)
	for decoder.More() {
		var record TraceRecord
		if err := decoder.Decode(&record); err != nil {
			break
		}
		records = append(records, record)
	}
	calculateFrameDurations(records)
	return records, nil
}

func calculateFrameDurations(records []TraceRecord) {
	opened := make(map[string]int)
	for i, r := range records {
		if r.FrameID == nil {
			continue
		}
		switch r.RecordType {
		case "FRAME_OPENED":
			opened[*r.FrameID] = i
		case "FRAME_CLOSED":
			if oi, ok := opened[*r.FrameID]; ok {
				dur := (r.Timestamp - records[oi].Timestamp) * 1000
				records[oi].Duration = dur
				records[i].Duration = dur
				delete(opened, *r.FrameID)
			}
		}
	}
}

// ── Utility helpers ────────────────────────────────────────────────────────

func strVal(v interface{}) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprintf("%v", v)
}

func shortID(id string) string {
	if len(id) > 8 {
		return id[:8] + "…"
	}
	return id
}

func truncate(s string, max int) string {
	if max <= 0 {
		return ""
	}
	if len(s) <= max {
		return s
	}
	return s[:max-1] + "…"
}

func sortedKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func formatDuration(d float64) string {
	if d <= 0 {
		return ""
	}
	if d < 1000 {
		return fmt.Sprintf("%.0fms", d)
	}
	return fmt.Sprintf("%.2fs", d/1000)
}

// ── Main ───────────────────────────────────────────────────────────────────

func main() {
	if len(os.Args) > 1 && os.Args[1] == "--version" {
		fmt.Println("Bifrost CLI v0.1.0")
		os.Exit(0)
	}

	p := tea.NewProgram(initialModel())
	if _, err := p.Run(); err != nil {
		fmt.Printf("Error: %v\n", err)
		os.Exit(1)
	}
}
