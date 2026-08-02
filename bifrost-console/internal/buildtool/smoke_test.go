package main

import (
	"archive/zip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestArchiveSidecarMismatchFailsClosed(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "archive.zip")
	if err := os.WriteFile(archive, []byte("archive"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(archive+".sha256", []byte(strings.Repeat("0", 64)+"  archive.zip\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := verifyArchiveSidecar(archive); err == nil {
		t.Fatal("mismatched checksum was accepted")
	}
}

func TestStrictExtractionRejectsTraversalAndUnexpectedFiles(t *testing.T) {
	for _, entryName := range []string{"../escape", "package/extra", "other/bifrost-console.exe"} {
		t.Run(strings.ReplaceAll(entryName, "/", "-"), func(t *testing.T) {
			root := t.TempDir()
			archive := filepath.Join(root, "bad.zip")
			file, err := os.Create(archive)
			if err != nil {
				t.Fatal(err)
			}
			writer := zip.NewWriter(file)
			header := &zip.FileHeader{Name: entryName}
			header.SetMode(0o755)
			entry, _ := writer.CreateHeader(header)
			_, _ = entry.Write([]byte("x"))
			_ = writer.Close()
			_ = file.Close()
			err = extractStrictArchive(archive, ".zip", filepath.Join(root, "out"), "package", map[string]os.FileMode{"bifrost-console.exe": 0o755})
			if err == nil {
				t.Fatalf("unsafe entry %q was accepted", entryName)
			}
		})
	}
}

func TestPackageModeRejectsVersionMismatchBeforeBuilding(t *testing.T) {
	err := run([]string{"package", "--expected-version", "not-the-product-version"})
	if err == nil || !strings.Contains(err.Error(), "does not match root POM version") {
		t.Fatalf("version mismatch error = %v", err)
	}
}
