
***

<img width="300" height="244" alt="image" src="https://github.com/user-attachments/assets/4a0df66e-6426-4e21-bf1e-6c33535be357" /> <img width="295" height="240" alt="image" src="https://github.com/user-attachments/assets/c6ae870e-17b7-45c2-a26f-9dfa6144e253" /> <img width="210" height="280" alt="image" src="https://github.com/user-attachments/assets/4ff68731-6168-4d69-a142-939a23c2f751" />


# 🛡️ DAuth

**DAuth** is a modern and simple authentication plugin that completely relieves players from the need to type `/login` or `/register` commands in chat. All interaction takes place through convenient built-in game menus.

### ⚡ Key Features

*   **No commands:** Complete refusal of chat commands for login. Everything is done through beautiful native Minecraft windows.
*   **Safe login:** The player physically cannot interact with the world, chat, or inventory until they pass verification, as they have not yet loaded onto the map.
*   **Account protection:**
    *   Automatic kick after **3 failed attempts** to enter the password.
    *   Login timer (default 60 seconds) — if the player is idle, they will be disconnected.
    *   Login ban if an account with such a nickname is already online.
*   **Convenient password change:** A player can change their password right in the game through a menu where they need to specify the old and new password.
*   **Support for 2FA:** Players can enable two-factor authentication via Google Authenticator. When 2FA is enabled, a code input window opens after entering the password.
*   **Session timers:** After a player logs out, they can re-enter without retyping the password for a configurable period (default 5 minutes).
*   **Configuration:** You can translate all labels, buttons, and window titles into any language, as well as limit the minimum and maximum password length.
*   **Option to provide a link to your Discord server:** If a player forgets their password or needs help, they can access your Discord server directly from the menu.

---

### 📜 Commands

| Command | Who uses it | Description |
| :--- | :--- | :--- |
| `/changepassword` | Players | Opens the menu to change the current password. |
| `/dreload` | Admins | Reloads the plugin config and messages. |
| `/logout` | Players | Logs out and kicks the player from the server. |
| `/unregister` | Players/Admins | Deletes a player's account.|
| `/2fa` | Players/Admins | Manage two-factor authentication (setup, remove). |

---

### 🔑 Permissions

| Permission | Who needs it | Description |
| :--- | :--- | :--- |
| `dauth.admin` | Admins | General admin access for certain console commands. |
| `dauth.admin.unregister` | Admins | Allows deleting accounts of other players via `/unregister <player>`. |
| `dauth.admin.2fa` | Admins | Allows resetting 2FA for other players via `/2fa remove <player>`. |

---

### ⚙️ Installation and Setup

1.  Drop the file into the `plugins` folder.
2.  Restart the server.
3.  In the `config.yml` file, you can change:
    *   Time allowed for password entry.
    *   Password length (minimum/maximum).
    *   All text messages and button names.
    *   Session duration and Discord link.
