package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	tea "charm.land/bubbletea/v2"
	"github.com/charmbracelet/lipgloss"
)

// Styles for the UI
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
			PaddingTop(1).
			PaddingLeft(2)

	subtitleStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("240")).
			PaddingLeft(2)

	itemStyle = lipgloss.NewStyle().
			PaddingLeft(1) // Compensate for arrow width

	selectedStyle = lipgloss.NewStyle().
			PaddingLeft(0).
			Foreground(lipgloss.Color("212")).
			Bold(true)

	commandMenuStyle = lipgloss.NewStyle().
				Foreground(lipgloss.Color("255")).
				Background(lipgloss.Color("238")).
				Padding(1).
				Border(lipgloss.RoundedBorder())

	// Two-pane styles
	paneStyle = lipgloss.NewStyle().
			Border(lipgloss.RoundedBorder()).
			Padding(1)

	topPaneStyle = lipgloss.NewStyle().
			Border(lipgloss.RoundedBorder()).
			Padding(1).
			Height(12).
			Width(80)

	bottomPaneStyle = lipgloss.NewStyle().
			Border(lipgloss.RoundedBorder()).
			Padding(1).
			Height(12).
			Width(80)

	frameTitleStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("14")).
			Bold(true)

	frameSelectedStyle = lipgloss.NewStyle().
				Foreground(lipgloss.Color("11")).
				Bold(true).
				Background(lipgloss.Color("240"))

	dataStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color("250")).
			PaddingLeft(2)
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

// TraceFile represents a trace file with metadata
type TraceFile struct {
	FileName     string
	FilePath     string
	SessionID    string
	TraceID      string
	RecordCount  int
	LastModified string
}

// App states
type state int

const (
	mainMenu state = iota
	traceViewer
	traceDetails
	commandMenu
)

// Model holds the application state
type model struct {
	state state
	// Trace viewer state
	traceFiles    []TraceFile
	traceCursor   int
	traceSelected int
	// Trace details state
	traceRecords  []TraceRecord
	frameCursor   int
	frameSelected int
	// Command menu state
	showCommandMenu bool
	commandCursor   int
	commandItems    []string
	quitting        bool
	width           int
	height          int
}

// Initial model state
func initialModel() model {
	return model{
		state:           mainMenu,
		showCommandMenu: false,
		commandItems: []string{
			"View Execution Traces",
			"Manage Skills",
			"Configuration Settings",
			"System Status",
			"Help & Documentation",
			"Quit Application",
		},
		commandCursor: 0,
	}
}

// Init initializes the model
func (m model) Init() tea.Cmd {
	return nil
}

// Update handles messages
func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height

	case tracesLoadedMsg:
		if msg.err != nil {
			return m, tea.Printf("Error loading traces: %v", msg.err)
		}
		m.traceFiles = msg.traces
		m.traceCursor = 0
		m.state = traceViewer
		return m, nil

	case traceDetailsLoadedMsg:
		if msg.err != nil {
			return m, tea.Printf("Error loading trace details: %v", msg.err)
		}
		m.traceRecords = msg.records
		m.frameCursor = 0
		m.state = traceDetails
		return m, nil

	case tea.KeyPressMsg:
		key := msg.String()

		// Handle command menu toggle
		if key == "/" {
			m.showCommandMenu = !m.showCommandMenu
			if m.showCommandMenu {
				m.commandCursor = 0
			}
			return m, nil
		}

		// Handle command menu when visible
		if m.showCommandMenu {
			switch key {
			case "/":
				m.showCommandMenu = false
				return m, nil
			case "q", "ctrl+c", "esc":
				if m.commandItems[m.commandCursor] == "Quit Application" {
					m.quitting = true
					return m, tea.Quit
				}
				m.showCommandMenu = false
				return m, nil
			case "up", "k":
				if m.commandCursor > 0 {
					m.commandCursor--
				}
			case "down", "j":
				if m.commandCursor < len(m.commandItems)-1 {
					m.commandCursor++
				}
			case "enter", " ":
				selected := m.commandItems[m.commandCursor]
				m.showCommandMenu = false
				return m, m.handleCommandSelection(selected)
			}
			return m, nil
		}

		// Handle regular navigation
		switch key {
		case "q", "ctrl+c":
			if m.state == traceViewer {
				m.state = mainMenu
				return m, nil
			}
			m.quitting = true
			return m, tea.Quit

		case "esc":
			if m.state == traceViewer {
				m.state = mainMenu
				return m, nil
			} else if m.state == traceDetails {
				m.state = traceViewer
				return m, nil
			}

		case "up", "k":
			if m.state == traceViewer {
				if m.traceCursor > 0 {
					m.traceCursor--
				}
			} else if m.state == traceDetails {
				if m.frameCursor > 0 {
					m.frameCursor--
				}
			}

		case "down", "j":
			if m.state == traceViewer {
				if m.traceCursor < len(m.traceFiles)-1 {
					m.traceCursor++
				}
			} else if m.state == traceDetails {
				if m.frameCursor < len(m.traceRecords)-1 {
					m.frameCursor++
				}
			}

		case "enter", " ":
			if m.state == traceViewer {
				if len(m.traceFiles) > 0 {
					m.traceSelected = m.traceCursor
					return m, m.handleTraceSelection()
				}
			}
		}
	}

	return m, nil
}

// View renders the UI
func (m model) View() tea.View {
	// Create persistent header
	header := headerStyle.Render("Bifrost CLI v0.1.0")

	// Create persistent footer
	footerText := "Press / for commands • q to quit"
	footer := footerStyle.Render(footerText)

	// Main content area
	var content string
	var title string
	var subtitle string

	switch m.state {
	case mainMenu:
		title = titleStyle.Render("Welcome to Bifrost CLI")
		subtitle = subtitleStyle.Render("Press / to open the command menu and get started")
		content = fmt.Sprintf("%s\n%s\n\n", title, subtitle)

	case traceDetails:
		title = titleStyle.Render("Trace Details")
		content = fmt.Sprintf("%s\n\n", title)

		if len(m.traceRecords) == 0 {
			content += subtitleStyle.Render("No trace records found")
		} else {
			// Calculate pane dimensions (30% top, 70% bottom, full width)
			// Adjust heights if command menu is visible
			menuHeight := 0
			if m.showCommandMenu {
				menuHeight = 12 // Approximate height of command menu
			}

			availableHeight := m.height - 10 - menuHeight // Account for header/footer/menu
			topPaneHeight := availableHeight * 3 / 10     // 30% for top pane
			bottomPaneHeight := availableHeight * 7 / 10  // 70% for bottom pane
			if topPaneHeight < 6 {
				topPaneHeight = 6
			}
			if bottomPaneHeight < 10 {
				bottomPaneHeight = 10
			}
			paneWidth := m.width - 4 // Account for borders
			if paneWidth < 60 {
				paneWidth = 60
			}

			// Top pane - Frames list (full width, 30% height)
			framesTitle := frameTitleStyle.Render("FRAMES")
			topPane := fmt.Sprintf("%s\n\n", framesTitle)

			// Calculate visible frames
			visibleFrames := topPaneHeight - 4 // Account for title and borders
			startFrame := 0
			if m.frameCursor >= visibleFrames {
				startFrame = m.frameCursor - visibleFrames + 1
			}
			endFrame := startFrame + visibleFrames
			if endFrame > len(m.traceRecords) {
				endFrame = len(m.traceRecords)
			}

			// Calculate maximum widths for table alignment
			maxSeqWidth := 0
			maxTypeWidth := 0
			for i := startFrame; i < endFrame; i++ {
				record := m.traceRecords[i]

				// Sequence width
				seqWidth := len(fmt.Sprintf("[%d]", record.Sequence))
				if seqWidth > maxSeqWidth {
					maxSeqWidth = seqWidth
				}

				// Type + route width
				typePart := record.RecordType
				if record.Route != nil {
					typePart += fmt.Sprintf(" - %s", *record.Route)
				}
				if len(typePart) > maxTypeWidth {
					maxTypeWidth = len(typePart)
				}
			}

			// Add some padding for visual separation
			maxTypeWidth += 2

			for i := startFrame; i < endFrame; i++ {
				record := m.traceRecords[i]
				cursor := ""
				if m.frameCursor == i {
					cursor = "→"
				}

				style := itemStyle
				if m.frameCursor == i {
					style = frameSelectedStyle
				}

				frameInfo := buildFrameInfo(record, maxSeqWidth, maxTypeWidth, 0)
				topPane += fmt.Sprintf("%s%s\n", cursor, style.Render(frameInfo))
			}

			// Bottom pane - Data for selected frame (full width, 70% height)
			var bottomPane string
			if m.frameCursor < len(m.traceRecords) {
				selectedRecord := m.traceRecords[m.frameCursor]

				// Build header with operationType or frameType
				headerInfo := fmt.Sprintf("DATA FOR FRAME %d - %s", selectedRecord.Sequence, selectedRecord.RecordType)

				// Try to get operationType or frameType from metadata
				if metadataMap, ok := selectedRecord.Metadata.(map[string]interface{}); ok {
					if operationType, exists := metadataMap["operationType"].(string); exists && operationType != "" {
						headerInfo += fmt.Sprintf(" [%s]", operationType)
					} else if frameType, exists := metadataMap["frameType"].(string); exists && frameType != "" {
						headerInfo += fmt.Sprintf(" [%s]", frameType)
					}
				}

				dataTitle := frameTitleStyle.Render(headerInfo)
				bottomPane = fmt.Sprintf("%s\n\n", dataTitle)

				// Format the data as JSON
				if selectedRecord.Data != nil {
					dataBytes, err := json.MarshalIndent(selectedRecord.Data, "", "  ")
					if err != nil {
						bottomPane += dataStyle.Render("Error formatting data")
					} else {
						// Truncate data if too long for pane
						dataStr := string(dataBytes)
						maxLines := bottomPaneHeight - 4 // Account for title and borders
						lines := strings.Split(dataStr, "\n")
						if len(lines) > maxLines {
							lines = lines[:maxLines]
							dataStr = strings.Join(lines, "\n")
						}
						bottomPane += dataStyle.Render(dataStr)
					}
				} else {
					bottomPane += dataStyle.Render("No data available")
				}
			}

			// Apply dynamic styling
			dynamicTopPane := topPaneStyle.Copy().
				Height(topPaneHeight).
				Width(paneWidth).
				Render(topPane)
			dynamicBottomPane := bottomPaneStyle.Copy().
				Height(bottomPaneHeight).
				Width(paneWidth).
				Render(bottomPane)

			// Combine panes vertically
			content += dynamicTopPane + "\n" + dynamicBottomPane
		}

		content += fmt.Sprintf("\n%s", subtitleStyle.Render("↑/↓: navigate frames  •  Esc: back  •  /: command menu"))

	case traceViewer:
		title = titleStyle.Render("Execution Traces")
		subtitle = subtitleStyle.Render("Select a trace file to view details")
		content = fmt.Sprintf("%s\n%s\n\n", title, subtitle)

		if len(m.traceFiles) == 0 {
			content += subtitleStyle.Render("No trace files found in ./traces directory")
		} else {
			for i, trace := range m.traceFiles {
				cursor := " "
				if m.traceCursor == i {
					cursor = "→"
				}

				style := itemStyle
				if m.traceCursor == i {
					style = selectedStyle
				}

				content += fmt.Sprintf("%s %s\n", cursor, style.Render(
					fmt.Sprintf("%s (%d records) - %s",
						trace.FileName,
						trace.RecordCount,
						trace.LastModified)))
			}
		}

		content += fmt.Sprintf("\n%s", subtitleStyle.Render("↑/↓: navigate  •  Enter: view  •  Esc: back  •  /: command menu"))
	}

	// Add command menu if visible
	if m.showCommandMenu {
		commandMenu := m.renderCommandMenu()
		content += "\n" + commandMenu
	}

	// Combine all parts
	fullContent := fmt.Sprintf("%s\n\n%s\n\n%s", header, content, footer)

	v := tea.NewView(fullContent)
	v.AltScreen = true
	v.MouseMode = tea.MouseModeCellMotion
	v.WindowTitle = "Bifrost CLI"
	return v
}

// Render command menu
func (m model) renderCommandMenu() string {
	menuTitle := titleStyle.Render("Command Menu")
	menuContent := fmt.Sprintf("%s\n\n", menuTitle)

	for i, item := range m.commandItems {
		cursor := " "
		if m.commandCursor == i {
			cursor = "→"
		}

		style := itemStyle
		if m.commandCursor == i {
			style = selectedStyle
		}

		menuContent += fmt.Sprintf("%s %s\n", cursor, style.Render(item))
	}

	menuContent += fmt.Sprintf("\n%s", subtitleStyle.Render("↑/↓: navigate  •  Enter: select  •  Esc/q: close"))

	return commandMenuStyle.Render(menuContent)
}

// Handle command menu selection
func (m model) handleCommandSelection(selected string) tea.Cmd {
	switch selected {
	case "View Execution Traces":
		return m.loadTraces()
	case "Manage Skills":
		return tea.Printf("Skill management coming soon!")
	case "Configuration Settings":
		return tea.Printf("Configuration editor coming soon!")
	case "System Status":
		return tea.Printf("System status coming soon!")
	case "Help & Documentation":
		return tea.Printf("Help system coming soon!")
	case "Quit Application":
		m.quitting = true
		return tea.Quit
	}
	return nil
}
func (m model) handleTraceSelection() tea.Cmd {
	if len(m.traceFiles) > m.traceSelected {
		trace := m.traceFiles[m.traceSelected]
		return m.loadTraceDetails(trace.FilePath)
	}
	return nil
}

// Load trace details from file
func (m model) loadTraceDetails(filePath string) tea.Cmd {
	return func() tea.Msg {
		records, err := loadTraceRecords(filePath)
		if err != nil {
			return traceDetailsLoadedMsg{records: []TraceRecord{}, err: err}
		}
		return traceDetailsLoadedMsg{records: records, err: nil}
	}
}

type traceDetailsLoadedMsg struct {
	records []TraceRecord
	err     error
}

type tracesLoadedMsg struct {
	traces []TraceFile
	err    error
}

// Load trace records from file
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

	// Calculate frame durations
	calculateFrameDurations(records)

	return records, nil
}

// Format duration string
func formatDuration(duration float64) string {
	if duration > 0 {
		if duration < 1000 {
			return fmt.Sprintf("%.0fms", duration)
		} else {
			return fmt.Sprintf("%.1fs", duration/1000)
		}
	}
	return "0ms"
}

// Build frame info with table-like alignment
func buildFrameInfo(record TraceRecord, maxSeqWidth int, maxTypeWidth int, maxRouteWidth int) string {
	// Format sequence number with padding
	seqStr := fmt.Sprintf("[%d]", record.Sequence)

	// Build record type part
	typePart := record.RecordType
	if record.Route != nil {
		typePart += fmt.Sprintf(" - %s", *record.Route)
	}

	// Format duration
	durationStr := formatDuration(record.Duration)

	// Calculate padding needed for alignment
	typePadding := maxTypeWidth - len(typePart)
	if typePadding < 0 {
		typePadding = 0
	}

	// Build aligned row
	return fmt.Sprintf("%s %s%s %s",
		seqStr,
		typePart,
		strings.Repeat(" ", typePadding),
		durationStr)
}
func calculateFrameDurations(records []TraceRecord) {
	// Create a map of frameId to opened record index
	openedFrames := make(map[string]int)

	for i, record := range records {
		if record.FrameID != nil {
			frameId := *record.FrameID

			switch record.RecordType {
			case "FRAME_OPENED":
				// Store the index of the opened frame
				openedFrames[frameId] = i

			case "FRAME_CLOSED":
				// Find matching opened frame
				if openedIndex, exists := openedFrames[frameId]; exists {
					openedRecord := &records[openedIndex]
					closedRecord := &records[i]

					// Calculate duration in milliseconds
					duration := (closedRecord.Timestamp - openedRecord.Timestamp) * 1000

					// Set duration for both opened and closed records
					openedRecord.Duration = duration
					closedRecord.Duration = duration

					// Remove from map since frame is closed
					delete(openedFrames, frameId)
				}
			}
		}
	}
}

// Load traces from directory
func (m model) loadTraces() tea.Cmd {
	return func() tea.Msg {
		traces, err := findTraceFiles("./traces")
		if err != nil {
			return tracesLoadedMsg{traces: []TraceFile{}, err: err}
		}
		return tracesLoadedMsg{traces: traces, err: nil}
	}
}

// Find trace files in directory
func findTraceFiles(dir string) ([]TraceFile, error) {
	var traces []TraceFile

	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return traces, nil // No traces directory is ok
		}
		return nil, err
	}

	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".execution-trace.ndjson") {
			continue
		}

		filePath := filepath.Join(dir, entry.Name())
		info, err := entry.Info()
		if err != nil {
			continue
		}

		// Parse filename to extract session and trace IDs
		name := strings.TrimSuffix(entry.Name(), ".execution-trace.ndjson")
		parts := strings.Split(name, ".")
		var sessionID, traceID string
		if len(parts) >= 2 {
			sessionID = parts[0]
			traceID = parts[1]
		} else {
			sessionID = name
			traceID = "unknown"
		}

		// Count records in the file
		recordCount, err := countTraceRecords(filePath)
		if err != nil {
			recordCount = 0
		}

		traces = append(traces, TraceFile{
			FileName:     entry.Name(),
			FilePath:     filePath,
			SessionID:    sessionID,
			TraceID:      traceID,
			RecordCount:  recordCount,
			LastModified: info.ModTime().Format("2006-01-02 15:04:05"),
		})
	}

	// Sort by last modified (newest first)
	sort.Slice(traces, func(i, j int) bool {
		return traces[i].LastModified > traces[j].LastModified
	})

	return traces, nil
}

// Count records in a trace file
func countTraceRecords(filePath string) (int, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return 0, err
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	count := 0
	for decoder.More() {
		var record TraceRecord
		if err := decoder.Decode(&record); err != nil {
			break
		}
		count++
	}

	return count, nil
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "--version" {
		fmt.Println("Bifrost CLI v0.1.0")
		os.Exit(0)
	}

	p := tea.NewProgram(initialModel())
	if _, err := p.Run(); err != nil {
		fmt.Printf("Error starting application: %v\n", err)
		os.Exit(1)
	}
}
