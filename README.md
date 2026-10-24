# Flip external screen assistant

## Function experience

[Online interactive tutorial](https://xiaomaoju.github.io/zflip5screenhelper/flipcover-tutorial.html) · [prototype page](https://xiaomaoju.github.io/zflip5screenhelper/)

The interactive prototype uses sample data to demonstrate the main functions and can be automatically demonstrated or experienced manually without being connected to the mobile phone. The software's external screen window, system operation and third-party widget compatibility are subject to the performance of Samsung's physical real machine.

## Software introduction

Flip External Screen Assistant is an external screen enhancement tool created for Samsung Galaxy Z Flip5. The project originated from the use demand after the internal screen was damaged. It is hoped that only relying on the external screen can open applications, switch tasks, view notifications and control common functions more conveniently, reducing reliance on unfolding the phone.

The software does not require root, and some system operations require Shizuku authorization; notifications, widgets, and floating interfaces require the user to grant corresponding permissions. Mainly for Android 16 /One UI 8.5 environment of Galaxy Z Flip5, actual compatibility depends on phone firmware and system permissions.

## Interface display

The following is a framed rendering of an existing version, including real machine screenshots and simulator examples. The current interface may be slightly changed.

| App Launcher · Native Cards | Control Center |
| ---| ---|
| <img src="dist/开发设计/readme-gallery/launcher.png" alt="Samsung native app launcher card" width="300"> | <img src="dist/开发设计/readme-gallery/controls.png" alt="External screen control center" width="300"> |

| Notification Center | External screen multitasking |
| ---| ---|
| <img src="dist/开发设计/readme-gallery/notifications.png" alt="Notification center example" width="300"> | <img src="dist/开发设计/readme-gallery/tasks.png" alt="Example of external screen task card" width="300"> |

## Main functions

### Shortcut bar and gesture entrance

Customize external screen shortcut buttons, order, location and display mode to quickly open frequently used functions. Enter the notification center and control center through two horizontal white bars respectively. The entrance position can be adjusted according to the screen direction. It supports sliding to expand and collapse, and a long press to switch the shortcut bar to show and hide.

### Control Center

Centrally operate brightness, volume, media playback and common system switches, and support NFC, mobile hotspot and external device management. Shortcut items can be added, removed, dragged, sorted and undone in the panel, and 3, 4, and 5 column layouts are supported. Some system operations rely on Shizuku, and the Android quick settings tile execution is an experimental feature.

### Apply Desktop, Folders and Dock

Browse the paged application desktop on the external screen and search for applications by name, alias, or pinyin. Supports manual sorting, drag-and-drop organization, folders, layout locking and application display preferences.

Commonly used applications in the sidebar and pinned applications in the Dock can be configured separately. The Dock provides access to pinned applications, recent applications, task cleanup, and application center, making it easy to launch applications and switch tasks on the external screen.

### External screen multitasking
Use task cards to browse recent tasks on the internal screen and the selected external screen. It supports horizontal swiping, clicking on the external screen to restore the original task, swiping up to close, sliding down to lock or unlock, and clearing the unlocked tasks in the list (including the internal screen). Internal screen tasks display small characters "(internal screen)" after the application name, and the card only displays the application icon; external screen task previews use system snapshots. Recent tasks retained by the system but not yet mounted to the screen can also be restored; the list is based on the data provided by the system. Removing a task does not guarantee that all service processes of the application will end, and the task lock is not equivalent to system persistence.

### Notifications and Media

View authorized activity notifications, open or clear notifications, and display notification badges on the app desktop. Media cards provide playback controls and detailed progress. Notification badges reflect eligible activity notifications and do not equate to system absences.

### Samsung native card

-**App Launcher Card**: Browse apps, open folders, use the sidebar and Dock in Samsung external screen cards, and share layouts and preferences with the floating launcher.
-**Widget Combination Card**: Provides 6 independent entrances, combines authorized Android desktop widgets within the 4×4 grid of each card, and adjusts the position and size.

### Mouse, trackpad and keyboard
Supports configuring pointer operations or directional navigation for the mouse and trackpad; the physical keyboard can use arrow keys to select, confirm key operations, and cancel key to return. The navigation covers the assistant's own interface and the assistant's native cards that meet the conditions, and complex layout editing retains touch operations.

### Appearance, Settings and Backup

Provides One UI style settings, glass materials and transition effects. You can adjust the assistant status bar, shortcut bar and layout preferences. Press the application to hide the status bar drawn by the assistant or hide the shortcut buttons. Supports application of direction rules, configuration import and export, and layout backup when launching an application from this tool.

Supports individually adjusting the display size of applications on the external screen: select five sizes in simple mode, customize DPI and resolution scaling in advanced mode, and restore the settings before adjustment. "Clear Portrait Screen (Experimental)" can maintain the holding direction, display the original application in a vertical area, and can still be resized after opening. Requires Shizuku and a compatible Samsung MultiStar external screen policy; the application must first join the MultiStar external screen launcher, and the actual effect depends on the firmware.

### Manual online update

Manually check for new versions in settings, download and verify the installation package, and then let the Android system confirm the installation. Networking is only used for user-initiated update checks and downloads. No notifications, configuration or device information is uploaded, no analytics tracking is added, no automatic authorization or lock screen bypassing is performed.