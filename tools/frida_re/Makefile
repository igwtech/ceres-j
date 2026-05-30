PY ?= python3
PIP ?= pip3
HERE := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

.PHONY: help install test test-unit test-functional clean fmt setup addon-sync

help:
	@echo 'frida_re make targets:'
	@echo '  install         pip install requirements'
	@echo '  setup PREFIX=…  download + place frida-gadget.dll'
	@echo '  test            run all tests (unit + functional)'
	@echo '  test-unit       run only unit-marked tests'
	@echo '  test-functional run only functional-marked tests'
	@echo '  addon-sync      copy canonical agent JS + game-dir gadget into'
	@echo '                  launcher-addon/ (run before publishing the addon)'
	@echo '  clean           remove caches / scratch files'

install:
	$(PIP) install -r requirements.txt

setup:
	@test -n "$(PREFIX)" || (echo "usage: make setup PREFIX=~/Neocron2/NeocronClient" && exit 2)
	./setup.sh $(PREFIX)

test:
	cd $(HERE) && $(PY) -m pytest

test-unit:
	cd $(HERE) && $(PY) -m pytest -m unit

test-functional:
	cd $(HERE) && $(PY) -m pytest -m functional

clean:
	find $(HERE) -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	rm -rf $(HERE).pytest_cache

# addon-sync mirrors the canonical agent JS into the launcher-addon
# directory. The gadget DLL is *not* re-synced from the game install
# (that's a separate, version-bumping operation); pass GADGET=… to
# overwrite it explicitly.
ADDON_DIR := $(HERE)launcher-addon
addon-sync:
	@test -d "$(ADDON_DIR)" || (echo "missing $(ADDON_DIR)"; exit 2)
	cp -v "$(HERE)agent/_agent.js" "$(ADDON_DIR)/agent/_agent.js"
ifdef GADGET
	@test -f "$(GADGET)" || (echo "GADGET not a file: $(GADGET)"; exit 2)
	cp -v "$(GADGET)" "$(ADDON_DIR)/winmm.dll"
endif
	@echo 'addon-sync: done. Run `git status` in $(ADDON_DIR) to review.'
