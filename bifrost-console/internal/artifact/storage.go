package artifact

import (
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// writableFile is the minimal interface for a partial artifact file being
// written to disk. It allows injecting short writes, sync failures, and close
// failures in tests.
type writableFile interface {
	io.Writer
	Sync() error
	Close() error
}

// fileSystem is the smallest internal seam needed to inject filesystem
// failures (short write, sync, close, rename, remove, and ENOSPC) without
// mocking service state transitions. The real implementation uses os
// operations beneath the verified transient subtree.
type fileSystem interface {
	mkdirAll(path string, perm os.FileMode) error
	createTemp(dir, pattern string) (writableFile, string, error)
	rename(oldpath, newpath string) error
	remove(path string) error
	open(path string) (io.ReadCloser, error)
	readDir(dir string) ([]os.DirEntry, error)
}

// realFileSystem wraps os and filepath operations.
type realFileSystem struct{}

func (realFileSystem) mkdirAll(path string, perm os.FileMode) error {
	return os.MkdirAll(path, perm)
}

func (realFileSystem) createTemp(dir, pattern string) (writableFile, string, error) {
	file, err := os.CreateTemp(dir, pattern)
	if err != nil {
		return nil, "", err
	}
	if err := file.Chmod(0o600); err != nil {
		file.Close()
		os.Remove(file.Name())
		return nil, "", err
	}
	return file, file.Name(), nil
}

func (realFileSystem) rename(oldpath, newpath string) error {
	return os.Rename(oldpath, newpath)
}

func (realFileSystem) remove(path string) error {
	return os.Remove(path)
}

func (realFileSystem) open(path string) (io.ReadCloser, error) {
	return os.Open(path)
}

func (realFileSystem) readDir(dir string) ([]os.DirEntry, error) {
	return os.ReadDir(dir)
}

// storage manages the service-owned artifacts directory beneath the verified
// transient subtree and performs all filesystem operations through the
// injectable fileSystem seam.
type storage struct {
	dir     string
	fs      fileSystem
	entropy func() ([]byte, error)
}

func newStorage(transientDir string, fs fileSystem, entropy func() ([]byte, error)) (*storage, error) {
	if fs == nil {
		fs = realFileSystem{}
	}
	if entropy == nil {
		entropy = cryptoRandBytes
	}
	dir := filepath.Join(transientDir, "artifacts")
	if err := fs.mkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("create artifact directory: %w", err)
	}
	return &storage{dir: dir, fs: fs, entropy: entropy}, nil
}

// createPartial creates a new protected random partial file under the artifacts
// directory. The returned path is never exposed to callers.
func (s *storage) createPartial() (writableFile, string, error) {
	return s.fs.createTemp(s.dir, "partial-*")
}

// installedName generates a random installed filename. The name is uncorrelated
// with the opaque handle so the handle never encodes a path.
func (s *storage) installedName() (string, error) {
	data, err := s.entropy()
	if err != nil {
		return "", fmt.Errorf("generate installed filename: %w", err)
	}
	return filepath.Join(s.dir, "installed-"+hex.EncodeToString(data)), nil
}

// rename atomically moves a partial file to its installed location. The
// destination must be on the same filesystem as the partial file.
func (s *storage) rename(oldpath, newpath string) error {
	return s.fs.rename(oldpath, newpath)
}

// remove deletes a file. A missing file is not an error.
func (s *storage) remove(path string) error {
	if path == "" {
		return nil
	}
	err := s.fs.remove(path)
	if err != nil && os.IsNotExist(err) {
		return nil
	}
	return err
}

// open opens an installed file for reading. Used by leases to provide read
// access without exposing the path.
func (s *storage) open(path string) (io.ReadCloser, error) {
	return s.fs.open(path)
}

// removeAllContents removes every file under the artifacts directory. Used
// during scope invalidation, shutdown, and restart cleanup.
func (s *storage) removeAllContents() error {
	entries, err := s.fs.readDir(s.dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	for _, entry := range entries {
		path := filepath.Join(s.dir, entry.Name())
		if err := s.fs.remove(path); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}
