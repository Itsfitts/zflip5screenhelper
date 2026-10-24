#Control center settings

Goal: The user decides the number of each row and the content of the tool. The visual grid is fixed to four rows, and a few items are left empty. The grid scrolls down independently when there are more than four rows. The size of the icons and grids does not change with the number of buttons, and is calculated based on the selected layout and the available space on the external screen. The settings only affect this tool and do not modify the system DPI, resolution, navigation bar or permissions.

## 0.8.0 Implemented

| Entrance | Behavior | Defaults and Boundaries |
| ---| ---| ---|
| Layout preview | Can be expanded/collapsed, reuse the official control panel layout builder | Default collapsed; displayed according to the set window width, clearly marked, does not prove the real machine gap/routing; does not perform operations, does not read the volume/media session |
| Layout defaults | Standard 4 columns, compact 5 columns, easy click 3 columns, atomic modification of column number/density/name/font size | Do not change button list, tool module, position and idle strategy; can be revoked after application |
| Number of icons per row | 3/4/5 columns, common to all rotation directions | Default 4; changing density/font/rotation does not change the number of columns |
| Arrangement density | Compact/Standard/Loose, coordinate the in-line space and the target size of graphics | Default standard; narrow areas are still adapted to the space; scrollable, not forced by cropping |
| Show button name | Show or hide | Displayed by default; after hiding, the text area is recovered and the complete accessible name, real status and long press details are retained |
| Name size | Small/Standard/Large, 9/10/12sp | Default standard, respect system font scaling; up to two lines, long content is omitted and the complete accessible name is retained |
| Tool area position | Auto/Side/Bottom | Default is automatic; the side follows the left and right hands; prompts and moves down when there is not enough space, retaining the number of columns |
| Tool area content | Independent switches for external screen brightness, media volume, and media card | All are enabled by default; no corresponding components will be created after being turned off, and the grid will fill up the available width when turned off |
| External screen brightness | Only affects the selected display | No longer requests brightness reading from the control panel after closing the module; does not fake a controllable state when connected without privileges |
| Media volume | Adjust mobile phone media volume | Setup instructions clarify the scope of the entire machine |
| Idle media storage | Shrink to visible text when there is no conversation; restore the entire card if there is a conversation | Closed by default; paused sessions are still controllable, do not press "Unplayed" to close; without permission, always shorten the authorization portal |
| Restore and Undo | Restore layout, name, and tool area defaults; undo the last effective adjustment once | Do not change button list, shortcut bar, share blur, status bar, gestures, display, permissions, app orientation, or system settings |
| Background blur | Inherited support capabilities and power saving downgrade strategy | Shared with application center; does not fall within the scope of partial layout restoration/undoing |
| Status bar and gestures | Jump to original settings | Control center time/notifications on the left, network/battery on the right, battery percentage always displayed |
| Shortcut buttons | Add, delete, move up, move down | Keep the user's existing list; add layout defaults without replacing buttons |

Default values: standard = 4 columns/standard density/display name/standard font; compact=5 columns/compact density/display name/small font; easy=3 columns/relaxed density/display name/large font. "Easy points" are space allocation preferences and do not claim to achieve the same touch area at all extreme sizes.

## Configuration and life cycle

-Layout snapshot is version 7，`panelColumns`Continuing to be the only authority for column numbers, `panelOptions` saves 8 new settings; the complete configuration is version 10. Old configurations 1–9 and old layouts 1–6 retain existing fields according to the corresponding versions, missing fields use stable default values, and existing fields are restored according to their respective versions. Invalid options or types are rejected in their entirety before submission.
-`panel_undo` is independent of global `layout_undo` and layout backup. Repeated application of the same value does not overwrite the undo; global recovery, global reset and configuration import cleanup expired partial undo. Partial undo only retains a bounded snapshot and does not export it with the configuration.
-Closing the media module means not mounting `MediaCardView`; releasing the system callback after the last media surface is unmounted. The idle storage entrance is still visible and event subscriptions are retained to respond to new sessions without increasing polling.
-Settings adopt discrete options, and each effective atomic modification saves and updates the settings preview; the opened floating panel follows the existing merge refresh path. There is no per-frame writing preference or new background graphics work.
-Preview will not actually trigger shortcut keys, volume adjustment, Shizuku, media playback or details. It is only used for arrangement comparison and does not prove Samsung's window routing and physical touch.

The status bar also provides left and right rounded corners for safety (default 16dp each, adjustable 0–48dp), which are saved with the global layout/configuration; local restore and undo in the control center does not modify the shared settings. See details [0.9.3 Verification records](dist/开发设计/VALIDATION-0.9.3.md)。

## Follow-up direction

Drag sorting, precise slider sizing, short name mapping, animation gears, material presets and named scenes are not implemented yet. The up/down sorting is currently retained; first verify the feel of the new combination in Flip5 before deciding whether to add more adjustment items. Notification inline replies are evaluated separately and are not bundled with the layout version.

For the construction and verification entrance, see [Development Notes](docs/development.md)，The scope and verification boundaries of this version can be found in [0.8.0 Verification Record](dist/开发设计/VALIDATION-0.8.0.md)。
