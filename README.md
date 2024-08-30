# 📸 PhotoExportSortCLI

[![Java Version](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build](https://img.shields.io/badge/Build-Maven-red.svg)](https://maven.apache.org/)

A premium command-line utility designed to elegantly reorganize Apple® Photos exports. Transform cluttered export structures into a clean, chronological, and localized directory hierarchy.

## ✨ Features

- **Chronological Sorting**: Automatically groups photos by year and ISO-formatted dates.
- **Smart Renaming**: Converts "Location, Date" folder names into "YYYY-MM-DD, Location" format.
- **Localization Support**: Handles various date formats based on your preferred locale (e.g., `de`, `en`).
- **Unicode Resilience**: Robustly handles Mac's canonical decomposition (NFD) for special characters like German umlauts.
- **Safety First**: Optional source deletion—keeps your original files safe by default.

## 🚀 Getting Started

### Prerequisites

- **Java 25** or higher
- **Maven** (for building)

### Installation

Clone the repository and build the project using Maven:

```bash
git clone https://github.com/thtesche/photosexportsortcli.git
cd photosexportsortcli
mvn clean package
```

This will generate a `jar-with-dependencies` in the `target` directory.

## 🛠 Usage

Run the tool using the following command structure:

```bash
java -jar target/sortcli-1.0-SNAPSHOT-jar-with-dependencies.jar \
  --sourceRoot /path/to/photos/export \
  --targetRoot /path/to/destination \
  --locale de \
  --deleteSource
```

### Options

| Option | Description |
| :--- | :--- |
| `--sourceRoot` | The root folder containing the Mac Photos app export. |
| `--targetRoot` | The destination folder for the reorganized structure. |
| `--locale` | Locale for date parsing (e.g., `de`, `en`, `fr`). |
| `--deleteSource` | (Optional) If specified, deletes the source folders after copying. |
| `--help` | Display help information and exit. |

## 📁 Transformation Example

**Before:**
```text
Photos Export/
├── Berlin, 15. März 2024/
└── 20. April 2024/
```

**After (Locale: `de`):**
```text
Sorted Photos/
└── 2024/
    ├── 2024-03-15, Berlin/
    └── 2024-04-20/
```

## ⚖️ License

Distributed under the MIT License. See `LICENSE` for more information.

---
*Created with ❤️ for organized memories.*
