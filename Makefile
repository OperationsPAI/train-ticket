SHELL := /usr/bin/env bash

.PHONY: check check-strict skeleton-check list-services

check: skeleton-check

check-strict:
	python3 scripts/check-skeleton.py --strict

skeleton-check:
	python3 scripts/check-skeleton.py

list-services:
	python3 scripts/list-services.py
