<h1><img width=80 src="https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/2a163eb9-515e-409a-b581-94a4fa513d91" /> <div>DiscordVerificator</div></h1>

<p>
  <a href="https://www.java.com/"><img src="https://img.shields.io/badge/Java-gray?color=C8273F" /></a>
  <a href="https://hub.spigotmc.org/javadocs/spigot/"><img src="https://img.shields.io/badge/Spigot_API-gray?color=F07427&logo=spigotmc&logoColor=FFFFFF" /></a>
  <a href="https://jda.wiki/"><img src="https://img.shields.io/badge/JDA-gray?color=5662F6&logo=discord&logoColor=FFFFFF" /></a>
  <a href="https://github.com/vshymanskyy/StandWithUkraine"><img src="https://raw.githubusercontent.com/vshymanskyy/StandWithUkraine/main/badges/StandWithUkraine.svg"></a>
</p>

 **DiscordVerificator** is a **Spigot** plugin that allows you to do player authentication using **Discord bot**.<br>

> [!WARNING]
> This plugin is **intended** to be used on **private servers** with the **manual player addition** because it involves you to manually link each player to their **Discord profile**.

 It was developed as an **alternative** for password-based authorization like `/login <password>` on servers with `online-mode` set to `false` (_in server.properties_).


 ## 🤔 How it works?

This plugin enables players to **link their usernames to their Discord profiles**. <br/>

The **linking process** is **controlled by the administrator** of the server. <br/>
In order **to link** the account, the **admin** should run `/link <Player> <Discord ID>`. ([how to get discord id?](https://youtu.be/RzTWH0g2xbo?si=oQT2rCSuf6B3Z5kY))
<img src="https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/50193702-ed0f-4b60-9884-58754a25328d">

Then, **when a player joins the server**, the **verification code appears**.<br>
To join the server, the player should run the seen command to the **Discord bot** you've configured: <br>
<img height=250 src="https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/1ad48c69-198b-48dc-8f5a-837312f094fa"><br>
<img src="https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/235dfef9-f390-4e2b-9bbe-d8e525425fe8"><br>
<img src="https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/c2758242-a3cd-4ef3-b6ec-83eb68e9438f">

> [!NOTE]
> **Verification** is required **once per IP change**

> [!CAUTION]
> The plugin **will prevent a player from joining** if it wasn't linked to **Discord** profile yet:
> <img height=200 src="https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/ef98c616-3c90-41cf-a111-ae49f416dc3c">

## 💻 Commands
- `/link <player> <discordId>` — links the player to its Discord profile. ([how to get discord id?](https://youtu.be/RzTWH0g2xbo?si=oQT2rCSuf6B3Z5kY))
- `/unlink <player>` — unlinks the player from its Discord profile.
- `/relink <old_player> <new_player>` — relinks the player from its minecraft username to another one.
- `/dvreload` — reloads the plugin (_including Discord bot_).
- `/dvinfo <player>` — shows information about the player.
- `/dvdecision <allow|block|unblock> <discordId|player>` — handles a shared-IP security decision.
  `allow` grants the selected Discord account a full shared-IP bypass; it does not assign a numeric
  per-user limit. A blocked account remains blocked even if this bypass is enabled.
- `/dvconfirm <player>` — server-console-only emergency command. Confirms the player's most recent
  login IP and enables manual access without Discord bot readiness, guild membership, or Discord
  confirmation codes.
- `/dvconfirm revoke <player>` — revokes manual access and restores the normal Discord checks.

Command identifiers are validated consistently:

- Minecraft usernames must contain 3-16 ASCII letters, digits, or underscores and are normalized
  to lowercase for database operations;
- Discord IDs must contain exactly 17-20 decimal digits;
- commands that accept either identifier reject all other values instead of treating them as an
  arbitrary Discord ID.
  
## 🔞 Permissions
- `discordVerificator.link` _(for **operators** by default)_ — Allows to use `/link <player> <discordId>`
- `discordVerificator.unlink` _(for **operators** by default)_ — Allows to use `/unlink <player>`
- `discordVerificator.reload` _(for **operators** by default)_ — Allows to use `/dvreload`
- `discordVerificator.info` _(for **operators** by default)_ — Allows to use `/info <player>`
- `discordVerificator.alerts` _(for **operators** by default)_ — Receives security alerts and allows
  use of `/dvdecision`

## 🔐 IP address privacy

The plugin processes IP addresses for IP-change verification and shared-IP detection. Staff-facing
output is masked by default:

- `/dvinfo` and in-game security alerts are limited to users with the corresponding operator-only
  permissions;
- Discord security alerts are sent only to the configured staff channel;
- IPv4 addresses are displayed in a form such as `192.168.*.*`, and IPv6 addresses expose no more
  than the first two hextets;
- historical IP and verification cooldown records are removed automatically after the configured
  retention period (30 days by default);
- the current allowed IP remains stored while the Discord account is linked because it is required
  to detect an IP change. Unlinking the last account removes the user and its IP data through the
  database foreign-key relationships.

The SQLite database and server backups still contain sensitive data and should only be accessible
to trusted administrators. Set `privacy.mask-ip-addresses-in-staff-messages` to `false` only when
staff genuinely need full addresses and the staff channels are appropriately restricted.

## 🛟 Emergency console confirmation

Use this only when a linked player has temporarily or permanently lost access to Discord:

1. Ask the player to attempt to join the server.
2. Within five minutes, run `dvconfirm <player>` from the local server console.
3. Ask the player to join again. The confirmed IP is now allowed and Discord bot readiness, guild
   membership, and Discord code checks are skipped for that linked user.
4. If the player's IP changes, the login is denied until the new attempt is confirmed from the
   console again.
5. Run `dvconfirm revoke <player>` to restore normal Discord authentication.

Blocked-account and shared-IP protections are never bypassed. The pending attempt exists only in
memory and disappears after five minutes or a server restart.

> [!WARNING]
> On an offline-mode server, a Minecraft username does not prove identity. Coordinate with the
> trusted player and run the command immediately after their known attempt. Confirming an
> attacker's newer spoofed attempt would authorize the attacker's IP.

## 📄 Default config
> [!IMPORTANT]
> You should replace `DISCORD_BOT_TOKEN` with your **Discord bot token**.<br>
> **Otherwise, nothing will work!**

```yml
# 1. Create a Discord bot on the Discord Developer Portal: https://discord.com/developers/applications
# 2. Get the token from the "Bot" tab
# 3. Insert the token below
# 4. Run the "/dvreload" command or reload the server
# 5. Give your players access to send a command to the bot (e.g., invite it to your Discord server)
token: "DISCORD_BOT_TOKEN"

# Maximum number of distinct linked Discord accounts that may share one IP address.
# /dvdecision allow <discordId | playerName> grants a full shared-IP bypass.
# Blocked status always takes priority over this bypass.
default-max-accounts-per-ip: 1

# Verification codes use unambiguous uppercase letters and digits.
# Allowed range: 2-16. Use 8 or more for stronger brute-force protection.
verification-code:
  length: 5
  expiration-seconds: 300

# If configured, multi-account alerts will be sent directly to your Discord staff channel
discord-alerts:
  channel-id: "" # E.g., "123456789012345678"
  admin-role-id: "" # The role ID required to click "Allow" / "Block" buttons

privacy:
  mask-ip-addresses-in-staff-messages: true
  ip-history-retention-days: 30

messages:
  "not-enough-permissions": "&cNot enough permissions!"
  "invalid-link-format": "&cInvalid format! Please use: /link <player> <discordId>"
  "invalid-unlink-format": "&cInvalid format! Please use: /unlink <player>"
  "invalid-relink-format": "&cInvalid format! Please use: /relink <old_player> <new_player>"
  "invalid-user-id-format": "&cInvalid Discord ID format!"
  "successfully-linked": "&aSuccessfully linked!"
  "successfully-unlinked": "&aSuccessfully unlinked!"
  "successfully-relinked": "&aSuccessfully relinked to the new username!"
  "player-already-linked": "&cThis player is already linked!"
  "player-was-not-linked": "&cThis player was never linked!"
  "account-not-linked": "&cYour account is not linked to a Discord profile yet."
  "bot-not-working": "&cThe Discord bot is not currently working!\nAsk the administrator to resolve this issue."
  "confirm-with-command": "&6Confirm your IP via our Discord bot\nUsage: &f&n/confirm %s"
  "wait-until-verification": "&cPlease wait until you can request a new code!\n&f&n%s seconds left."
  "error-occurred": "An error occurred!"
  "its-not-your-account": "The account you're trying to confirm is not linked to your Discord profile!"
  "allowed": "Allowed!"
  "allowed-to-join-from-ip": "Successfully allowed to join from `%s`!"
  "confirm-command": "Command to verify you on the Minecraft server"
  "verification-code-you-got": "Verification code you've received from the server"
  "invalid-code": "Invalid Code!"
  "invalid-code-description": "This verification code is not valid!"
  "invalid-usage": "Invalid usage!"
  "provide-code-please": "Please provide the verification code!"
  "user-not-found": "User not found!"
  "user-not-found-description": "It seems like your account hasn't been linked to any Minecraft username yet."
  "reloaded": "&#14C60D[DiscordVerificator] Reloaded!"
  "account-blocked": "&cYour account has been blocked by an administrator."
  "security-check": "&cSecurity check triggered. Please wait for administrator approval."
  "admin-alert-multi-ip": "&c&l[Security] &fMulti-account detected for &e%s&f (IP: %s) shared with &7%s&f."
  "admin-alert-blocked-assoc": "&c&l[Security] &fUser &e%s&f is connecting from an IP associated with BLOCKED user &7%s&f."
  "button-allow": "&a[ALLOW]"
  "button-block": "&c[BLOCK]"
  "hover-allow": "&7Click to allow this user to share this IP."
  "hover-block": "&7Click to block this user."
  "action-success": "&aAction completed successfully."
  "action-failed": "&cFailed to execute action."
```

## ☂ Getting started

> [!IMPORTANT]
> Before getting started, make sure that the plugin's version is **compatible** with your server version.

1. Create a **new discord application** on <a href="https://discord.com/developers/applications/">Discord Developer Portal</a><br>
![image](https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/3322da7c-95b3-4ee0-a22a-c868c5f43aae)<br>
![image](https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/fa05b770-0ad0-42a6-833e-101ba06eee41)
1. Go to the **"Bot"** tab and click on the **"Reset token"** button<br>
![image](https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/f75a5bca-a28a-42b2-aa04-479842688280)<br>
![image](https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/be31fca3-2f50-4004-a9c9-fdf076bde60d)
1. Copy the token<br>
![image](https://github.com/MrQuackDuck/DiscordVerificator/assets/61251075/f2b15ed9-5999-4093-8c78-3ee27c490c28)
1. Download the plugin from <a href="https://github.com/MrQuackDuck/DiscordVerificator/releases">Releases</a> tab or from <a href="https://www.spigotmc.org/resources/discord-verificator.117794/">Spigot</a> page.
1. Put downloaded `.jar` into `./plugins` folder of your server.
1. Restart your server or enter `reload` command.
1. Go to `./plugins/DiscordVerificator` folder and open `config.yml`
1. Replace `DISCORD_BOT_TOKEN` with the token you've copied previously
1. Save the config and run `dvreload` command<br><br>
1. **Everything is done!** Now you can link players with the `link` command and<br> **invite** this bot **to your Discord server** (_to make them able to run `confirm <code>` command to the **Discord bot**_)
