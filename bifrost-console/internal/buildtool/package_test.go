package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestSupportedReleaseTargetsAreExact(t *testing.T) {
	cases := []struct{ goos, goarch, label, extension string }{
		{"windows", "amd64", "windows-x86_64", ".zip"},
		{"linux", "amd64", "linux-x86_64", ".tar.gz"},
		{"darwin", "arm64", "macos-arm64", ".tar.gz"},
	}
	for _, current := range cases {
		target, err := supportedReleaseTarget(current.goos, current.goarch)
		if err != nil {
			t.Fatal(err)
		}
		if target.label != current.label || target.extension != current.extension {
			t.Fatalf("target = %+v, want %s%s", target, current.label, current.extension)
		}
	}
	for _, unsupported := range [][2]string{{"windows", "arm64"}, {"linux", "arm64"}, {"darwin", "amd64"}} {
		if _, err := supportedReleaseTarget(unsupported[0], unsupported[1]); err == nil {
			t.Fatalf("accepted unsupported target %s/%s", unsupported[0], unsupported[1])
		}
	}
}

func TestReleasePackagesAreDeterministicAndContainOnlyRuntimeFiles(t *testing.T) {
	for _, coordinates := range [][2]string{{"windows", "amd64"}, {"linux", "amd64"}, {"darwin", "arm64"}} {
		t.Run(coordinates[0]+"-"+coordinates[1], func(t *testing.T) {
			target, _ := supportedReleaseTarget(coordinates[0], coordinates[1])
			root := t.TempDir()
			executable := writePackageInput(t, root, "binary", "executable")
			license := writePackageInput(t, root, "license", "license text")
			readme := writePackageInput(t, root, "readme", "runtime instructions")
			request := packageRequest{version: "1.2.3-rc.1", target: target, executable: executable,
				license: license, readme: readme, outputDirectory: filepath.Join(root, "dist")}
			first, err := writeReleasePackage(request)
			if err != nil {
				t.Fatal(err)
			}
			firstBytes, _ := os.ReadFile(first.archive)
			second, err := writeReleasePackage(request)
			if err != nil {
				t.Fatal(err)
			}
			secondBytes, _ := os.ReadFile(second.archive)
			if !bytes.Equal(firstBytes, secondBytes) {
				t.Fatal("identical package inputs produced different archive bytes")
			}
			expectedName := "bifrost-console-1.2.3-rc.1-" + target.label + target.extension
			if filepath.Base(first.archive) != expectedName || filepath.Base(first.sidecar) != expectedName+".sha256" {
				t.Fatalf("unexpected output names: %s, %s", first.archive, first.sidecar)
			}
			digest := sha256.Sum256(firstBytes)
			if first.digest != hex.EncodeToString(digest[:]) {
				t.Fatalf("digest = %s", first.digest)
			}
			sidecar, _ := os.ReadFile(first.sidecar)
			if string(sidecar) != first.digest+"  "+expectedName+"\n" {
				t.Fatalf("sidecar = %q", sidecar)
			}
			top := strings.TrimSuffix(expectedName, target.extension)
			executableName := "bifrost-console"
			if target.goos == "windows" {
				executableName += ".exe"
			}
			want := map[string]os.FileMode{
				top + "/LICENSE": 0o644, top + "/README.md": 0o644, top + "/" + executableName: 0o755,
			}
			got := archiveEntries(t, first.archive, target.extension)
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("archive entries = %#v, want %#v", got, want)
			}
		})
	}
}

func TestReleasePackageRejectsUnsafeVersionsAndNonRegularInputs(t *testing.T) {
	target, _ := supportedReleaseTarget("linux", "amd64")
	root := t.TempDir()
	regular := writePackageInput(t, root, "regular", "x")
	request := packageRequest{version: "../escape", target: target, executable: regular, license: regular, readme: regular, outputDirectory: root}
	if _, err := writeReleasePackage(request); err == nil {
		t.Fatal("unsafe version was accepted")
	}
	request.version = "1.2.3"
	request.executable = root
	if _, err := writeReleasePackage(request); err == nil || !strings.Contains(err.Error(), "regular non-symlink") {
		t.Fatalf("directory input error = %v", err)
	}
}

func writePackageInput(t *testing.T, root, name, contents string) string {
	t.Helper()
	filename := filepath.Join(root, name)
	if err := os.WriteFile(filename, []byte(contents), 0o644); err != nil {
		t.Fatal(err)
	}
	return filename
}

func archiveEntries(t *testing.T, filename, extension string) map[string]os.FileMode {
	t.Helper()
	result := make(map[string]os.FileMode)
	if extension == ".zip" {
		reader, err := zip.OpenReader(filename)
		if err != nil {
			t.Fatal(err)
		}
		defer reader.Close()
		for _, file := range reader.File {
			if strings.Contains(file.Name, "\\") || strings.HasPrefix(file.Name, "/") || strings.Contains(file.Name, "../") {
				t.Fatalf("unsafe ZIP path %q", file.Name)
			}
			result[file.Name] = file.Mode().Perm()
		}
		return result
	}
	file, err := os.Open(filename)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	gzipReader, err := gzip.NewReader(file)
	if err != nil {
		t.Fatal(err)
	}
	tarReader := tar.NewReader(gzipReader)
	for {
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		if header.Typeflag != tar.TypeReg || strings.HasPrefix(header.Name, "/") || strings.Contains(header.Name, "../") {
			t.Fatalf("unsafe TAR entry %+v", header)
		}
		result[header.Name] = os.FileMode(header.Mode)
	}
	return result
}
