# MacFotoCli 📸🚀

[![Java CI with Maven](https://github.com/thtesche/macfotocli/actions/workflows/maven.yml/badge.svg)](https://github.com/thtesche/macfotocli/actions/workflows/maven.yml)
[![Java Version](https://img.shields.io/badge/Java-25-blue.svg)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**MacFotoCli** (formerly SortCli) is a powerful, modern Java 25 command-line tool designed to reorganize and enhance Apple Photos exports.

It offers two primary functions:
1. **Sort:** Reorganizes messy export directories into a clean, chronological structure (`YYYY/YYYY-MM-DD, Location`).
2. **Tag:** Uses a local LLM (Ollama) to automatically extract descriptive keywords from file paths and writes them as EXIF tags to the images using `exiftool`.

---

## 🛠 Prerequisites

To run and build this project, you need:
*   **Java 25** (e.g., Eclipse Temurin 25)
*   **Ollama** (Running locally on `localhost:11434` with a model like `gemma4`) - *Required for the `tag` command*
*   **exiftool** (e.g., `brew install exiftool`) - *Required for the `tag` command*

---

## 🚀 Quick Start & Usage

### 1. Build the project
Thanks to the included Maven Wrapper, you don't need to install Maven. Just run:
```bash
./mvnw clean package
```

### 2. Verify Installation (Doctor)
Check if Ollama and exiftool are correctly installed and reachable:
```bash
java -jar target/macfotocli-1.3.0-jar-with-dependencies.jar doctor
```

### 3. Sort Photos
Reorganize your exported photos. 

**Example:**
```bash
java -jar target/macfotocli-1.3.0-jar-with-dependencies.jar sort \
  --sourceRoot=/path/to/apple/export \
  --targetRoot=/path/to/clean/structure \
  --locale=de
```
This will transform `Berlin, 15. März 2024` into `2024/2024-03-15, Berlin`.

### 4. Auto-Tag Photos (AI powered)
Scan a directory and let Ollama automatically generate semantic tags based on the folder/file names, writing them to the EXIF data.

**✨ Smart Tag Management:**
* **Non-destructive:** Existing EXIF keywords are fully preserved.
* **Smart Deduplication:** New tags are only written if they do not already exist in the file.
* **Case-Insensitive:** Prevents duplicate variations like `Berlin` and `berlin`.

**Example (Dry Run - just see the tags, don't write, and ignore specific words):**
```bash
java -jar target/macfotocli-1.3.0-jar-with-dependencies.jar tag \
  --model=gemma4 \
  --ignore="Backup,Urlaub,Fotos" \
  --dryRun \
  /path/to/photos
```

**Example (Actually write tags for multiple directories using shell wildcards):**
```bash
java -jar target/macfotocli-1.3.0-jar-with-dependencies.jar tag \
  /path/to/200*
```

### 5. Fix Dates
Some images may lack EXIF dates but have them encoded in their folder structure (e.g. `YYYY-MM-DD`). The `fixdate` command scans directories, extracts the expected date from the directory path, and verifies it against the image's EXIF `DateTimeOriginal`. If missing or mismatched, it updates the EXIF metadata to match the folder structure.

**✨ Key Features:**
* **Non-destructive Time:** Existing valid times (e.g., `13:45:00`) are preserved if only the date part is wrong.
* **Safe Defaults:** If no time was present at all, a default of `12:00:00` is used.
* **Dry Run Support:** You can simulate the process first without modifying files.

**Example (Dry Run):**
```bash
java -jar target/macfotocli-1.3.0-jar-with-dependencies.jar fixdate \
  --dryRun \
  /path/to/200*
```

**Example (Actually write dates):**
```bash
java -jar target/macfotocli-1.3.0-jar-with-dependencies.jar fixdate \
  /path/to/200*
```

---

## 🏷️ Releasing
To create a new release, bump the version in `pom.xml`, and create a Git tag, simply run the interactive release script:
```bash
./release.sh
```
It will guide you through setting the release version and the next development snapshot version.

---

## 🤝 Contributing
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
