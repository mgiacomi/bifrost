# Bifrost CLI

Interactive command-line interface for Bifrost built with Bubbletea.

## Features

- **Skill Management**: Browse and manage Bifrost skills
- **Trace Viewer**: View execution traces and logs
- **Configuration Editor**: Modify Bifrost settings
- **System Status**: Monitor system health and metrics
- **Help System**: Interactive documentation

## Installation

```bash
go install github.com/mgiacomi/bifrost-cli@latest
```

## Usage

```bash
# Run the interactive CLI
bifrost-cli

# Show version
bifrost-cli --version
```

## Development

```bash
# Run from source
go run main.go

# Build
go build -o bifrost-cli main.go
```

## Navigation

- **↑/↓ or k/j**: Navigate menu items
- **Enter or Space**: Select menu item  
- **q or Ctrl+C**: Quit application

## Architecture

Built with [Bubbletea](https://github.com/charmbracelet/bubbletea) using the Elm architecture:

- **Model**: Application state
- **Update**: Message handling and state transitions
- **View**: UI rendering with Lipgloss styling

## Integration

This CLI tool integrates with the Bifrost ecosystem:

- Reads configuration from `application.yml`
- Accesses execution traces from temp directory
- Manages skill definitions in YAML format
- Communicates with running Bifrost instances
