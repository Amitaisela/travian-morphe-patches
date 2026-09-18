# 👋🧩 Morphe Patches template

Template repository for Morphe Patches.

## ❓ About

Patches for apps I like.

<!-- TODO: Update this about section with a brief introduction/summary about this repo and what it offers. -->

### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=Amitaisela/travian-morphe-patches

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.5.1](https://github.com/Amitaisela/travian-morphe-patches/releases/tag/v1.5.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;2 patches total
<details open>
<summary>📦 Travian: Legends&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 4.0.0 | 4.0.1 | 4.0.2 |
| :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Build/troop queue notifications](#build-troop-queue-notifications) | Notifies you when a building upgrade or troop training queue finishes, with the building/unit name, level, and village, and warns you about incoming attacks and raids (who, from where, and when they arrive). Uses the session you're already logged in with in the game — no separate login, no password ever handled by this patch. Checks run quietly in the background: one is scheduled for just after each build/training is due to finish, plus a regular check every 5 minutes (Android may delay background work slightly). Nothing is shown unless something actually finished. The first time you open the app it asks once for notification permission and to exempt the app from battery optimization, so the background checks aren't killed by the system. |  |
| [Travian notifier manifest entry](#travian-notifier-manifest-entry) | Adds the permission needed to ask for a battery optimization exemption. |  |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

UserXYZ Patches are licensed under the [GNU General Public License v3.0](LICENSE)
