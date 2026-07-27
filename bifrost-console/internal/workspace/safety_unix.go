//go:build !windows

package workspace

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

func unsafeEntry(info os.FileInfo) bool {
	return info.Mode()&os.ModeSymlink != 0
}

func protectNewDirectory(path string) error { return os.Chmod(path, 0o700) }
func protectNewFile(path string) error      { return os.Chmod(path, 0o600) }
func verifyProtectedDirectory(path string) error {
	return verifyOwnedMode(path, true, 0o700)
}
func verifyProtectedFile(path string) error {
	return verifyOwnedMode(path, false, 0o600)
}

func verifyOwnedMode(path string, directory bool, mode os.FileMode) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != uint32(os.Getuid()) {
		return fmt.Errorf("path is not owned by the current user")
	}
	if info.IsDir() != directory || info.Mode().Perm() != mode {
		return fmt.Errorf("path protection is not owner-only")
	}
	return nil
}

func unsafePath(path string) (bool, error) {
	current := string(filepath.Separator)
	for _, part := range strings.Split(strings.TrimPrefix(path, current), string(filepath.Separator)) {
		if part == "" {
			continue
		}
		current = filepath.Join(current, part)
		info, err := os.Lstat(current)
		if err != nil {
			return false, err
		}
		if unsafeEntry(info) {
			return true, nil
		}
	}
	return false, nil
}
