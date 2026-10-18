SHELL := /bin/bash

APP_ID := com.juncehome.lifepo4ble
MAIN_ACTIVITY := .MainActivity
WEB_DIR := web/liveline-power-spike
GRADLEW := ./gradlew
ADB := adb
JAVA_HOME ?= /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME

.DEFAULT_GOAL := help

.PHONY: help web-build build install run live devices logs loc clean

help:
	@printf '%s\n' \
		'Available targets:' \
		'  make web-build  Build the Liveline spike (web/liveline-power-spike); no longer bundled in the app' \
		'  make build      Build the debug Android APK' \
		'  make install    Install the debug APK with adb' \
		'  make run        Build, install, and launch the app on Dashboard' \
		'  make live       Build, install, and launch the app directly on Live' \
		'  make devices    Show connected adb devices' \
		'  make logs       Stream PID-filtered app logs' \
		'  make loc        Count source files and lines with cloc' \
			'  make clean      Run ./gradlew clean'

web-build:
	cd "$(WEB_DIR)" && npm run build

# The app no longer bundles the Liveline WebView asset, so build does not depend on web-build.
build:
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

loc:
	cloc . \
		--exclude-dir=.git,build,.gradle,.kotlin,.idea,.worktrees,.claude,.agents,.pytest_cache,node_modules,.venv,venv,__pycache__,.streamlit \
		--exclude-ext=json,lock,svg,png,jpg,webp \
		--not-match-d='(^|/)(build|\.gradle|\.kotlin|\.venv|node_modules|\.worktrees|\.claude|\.agents|\.git)(/|$$)'

clean:
	$(GRADLEW) clean
