# Whorl privacy policy

_Last updated: October 15, 2026_

Whorl is an SSH and mosh client. It has no accounts, no analytics, no advertising and no server of its
own.

**What it stores, and where.** The machines you add (name, addresses, port, user name, optional saved password,
startup command), your device key, and the host keys of servers you have connected to. All of it stays on your
device, in app-private storage. Passwords and keys are encrypted with a key held in the Android Keystore. None
of it is backed up to Google or anyone else; uninstalling the app deletes it.

**What it sends, and to whom.** Whorl connects only to the machines you add, over SSH and, for mosh
sessions, mosh's encrypted UDP protocol. To pick which of a machine's addresses to use, and to notice when one
stops working after the network changes, it opens a connection to that address's SSH port. It sends nothing to the developer or to any third party. Links you
choose to open from the terminal open in your browser.

**Permissions.** Network access, to reach your machines. Network state, to notice when the phone changes
networks and reconnect. A foreground service and notification, so sessions stay connected while the app is in
the background. Notifications, for that and for alerts from programs running in your sessions.

**Children.** Whorl is a developer tool and is not directed at children.

**Changes.** If this policy changes, the new version will be posted here with a new date.

**Contact.** Open an issue at https://github.com/exterkamp/whorl/issues.
