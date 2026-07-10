SHELL := /bin/bash

APP_ID := com.juncehome.lifepo4ble
MAIN_ACTIVITY := .MainActivity
WEB_DIR := web/liveline-power-spike
GRADLEW := ./gradlew
ADB := adb
JAVA_HOME ?= /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME

.DEFAULT_GOAL := help

.PHONY: help web-build build install run live devices logs clean

help:
	@printf '%s\n' \
		'Available targets:' \
		'  make web-build  Build the React WebView assets into app/src/main/assets/liveline-power' \
		'  make build      Build the web assets and the debug Android APK' \
		'  make install    Install the debug APK with adb' \
		'  make run        Build, install, and launch the app on Dashboard' \
		'  make live       Build, install, and launch the app directly on Live' \
		'  make devices    Show connected adb devices' \
		'  make logs       Stream PID-filtered app logs' \
		'  make clean      Run ./gradlew clean'

web-build:
	cd "$(WEB_DIR)" && npm run build

build: web-build
	$(GRADLEW) :app:assembleDebug

install: build
	$(ADB) install -r app/build/outputs/apk/debug/app-debug.apk

run: install
	$(ADB) shell am start -S -n $(APP_ID)/$(MAIN_ACTIVITY)

live: install
	$(ADB) shell am start -S -n $(APP_ID)/$(MAIN_ACTIVITY) --es $(APP_ID).extra.START_DESTINATION live

devices:
	$(ADB) devices

logs:
	$(ADB) logcat --pid "$$($(ADB) shell pidof -s $(APP_ID))" -v threadtime

clean:
	$(GRADLEW) clean
