# WiFi Scanner & Lock App

An Android app that scans WiFi networks, shows weakest signals first, and allows locking to a specific network with aggressive reconnection.

## Features

- Scan WiFi networks and display them sorted by signal strength (weakest first)
- Select a specific network to lock onto
- Aggressive reconnection if connection is lost
- Background service maintains connection priority

## Build Instructions

1. Open the project in Android Studio
2. Sync Gradle files
3. Build and run on device

## Permissions Required

- ACCESS_WIFI_STATE
- CHANGE_WIFI_STATE
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION
- INTERNET

## Usage

1. Tap "Scan WiFi Networks" to scan
2. Networks are listed with weakest signals at top
3. Tap a network to select it
4. Tap "Lock to Selected Network" to maintain connection
