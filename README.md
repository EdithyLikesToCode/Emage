# Emage 🎨

**Emage** is a high-performance Minecraft plugin that displays images and animated GIFs on item frames. It features automatic grid detection, high-quality dithering, advanced compression, and smooth 60 FPS animation playback.

[![Modrinth](https://img.shields.io/modrinth/dt/emage?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/plugin/emage)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-Download-orange)](https://www.spigotmc.org/resources/emage.130410/)
[![GitHub](https://img.shields.io/github/v/release/EdithyLikesToCode/Emage?logo=github&label=GitHub)](https://github.com/EdithyLikesToCode/Emage)

---

## ✨ Features

### Image Display
- **🖼️ Static Images** - Display any PNG, JPG, or WebP image from the web
- **🎬 Animated GIFs** - Full GIF support with smooth playback and frame synchronization
- **🧩 Grid Support** - Automatically detects connected item frames (up to 15x15)
- **📐 Auto-Scaling** - Automatically fills detected frame grid or specify custom dimensions

### Quality & Processing
- **🎨 Advanced Dithering** - Floyd-Steinberg dithering with edge-aware error diffusion
- **🔍 Smart Sharpening** - Unsharp mask sharpening to counteract resize blur
- **🖌️ Edge Enhancement** - Laplacian edge enhancement for crisp details on maps
- **📊 Three Quality Modes** - Fast, Balanced, and High quality options

### Performance
- **🚀 Zero Lag** - All image processing happens asynchronously on worker threads
- **⚡ Distance-Based FPS** - 60 FPS when close, 20 FPS when far away
- **🎯 Adaptive Performance** - Automatically adjusts quality based on server load
- **👁️ Proximity Tracking** - Only updates animations for nearby players
- **🔄 SyncGroup System** - Entire grids share single frame calculation
- **💾 Memory Pooling** - Reuses buffers to reduce garbage collection

### Storage & Persistence
- **💾 Persistent Storage** - Images survive server restarts
- **📦 Advanced Compression** - Up to 90% smaller files with palette reduction and delta encoding
- **🗂️ Grid Files** - Multiple maps stored in single compressed files
- **🧹 Cleanup Tools** - Automatically remove unused map files

---

## 📸 Screenshots

| Normal Image | Large Image |
|:---:|:---:|
| ![Static](https://cdn.imgchest.com/files/1047bf5e0c63.png) | ![Grid](https://cdn.imgchest.com/files/554f1415a4fd.png) |

---

| Command                         | Description                       | Permission    |
|:--------------------------------|:----------------------------------|:--------------|
| `/emage <url> [size] [quality]` | Upload an image onto item frames  | `emage.use`   |
| `/emage help`                   | Show information about the plugin | `emage.use`   |
| `/emage reload`                 | Reload the plugin                 | `emage.admin` |
| `/emage cleanup`                | Remove unused map files           | `emage.admin` |
| `/emage stats`                  | View storage statistics           | `emage.admin` |
| `/emage perf`                   | View performance statistics       | `emage.admin` |
| `/emage migrate`                | Convert legacy format files       | `emage.admin` |
| `/emage update`                 | Check for updates                 | `emage.admin` |


| Flag         | Aliases             |
|:-------------|:--------------------|
| `--fast`     | -f, --low, --speed  |
| `--balanced` | -b, --normal        |
| `--high`     | -h, --hq, --quality |

## 📥 Installation

1. Download the latest release from [Modrinth](https://modrinth.com/plugin/emage), [SpigotMC](https://www.spigotmc.org/resources/emage.130410/), or [GitHub](https://github.com/EdithyLikesToCode/Emage/releases)
2. Place `Emage.jar` in your server's `plugins` folder
3. Restart your server
4. (Optional) Configure settings in `plugins/Emage/config.yml`

---

## 🎮 Usage

### Basic Usage

1. Place item frames on a wall in a grid pattern
2. Look at one of the item frames
3. Run `/emage <image-url>`

### Examples

```bash
# Auto-detect grid size
/emage https://example.com/image.png

# Force specific grid size
/emage https://example.com/image.png 3x3

# Display animated GIF
/emage https://example.com/animation.gif

# High quality mode
/emage https://example.com/photo.jpg --high

# Fast processing mode
/emage https://example.com/image.png 5x5 --fast
```


### 🐛 Reporting Issues
Found a bug? Please report it on [GitHub Issues](https://github.com/EdithyLikesToCode/Emage/issues/new/choose) with:

- Server version (e.g., Paper 1.20.6)
- Emage version (/emage update shows current version)
- Steps to reproduce
- Error logs from logs/latest.log (if any)
- Screenshots (if visual issue)

### 💖 Support
If you find Emage useful, consider:

- ⭐ Starring the repository on [GitHub](https://github.com/EdithyLikesToCode/Emage)
- 📝 Leaving a review on [SpigotMC](https://www.spigotmc.org/resources/emage.130410/) or [Modrinth](https://modrinth.com/plugin/emage)
- 🐛 Reporting bugs and suggesting features
- 💬 Sharing with other server owners