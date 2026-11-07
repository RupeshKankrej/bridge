# ADR 0003 — AirDrop interoperability in two tiers

**Status:** accepted · 2026-11-06

## Context
The user wants the Pad 3 visible to Apple devices via AirDrop. Google's Quick Share–AirDrop interop exists for some OnePlus devices but, as of 6 Nov 2026, not the Pad 3. Apple's Contacts Only mode cannot see non-Apple devices.

## Decision
Tier A: if Quick Share gains AirDrop on the Pad 3, Bridge drives Quick Share instead of reimplementing. Tier B (plan of record): a native AirDrop stack (BLE advert + Wi-Fi Aware + AirDrop HTTPS), spiked in Phase 3 and shipped only if the spike passes. Visibility options are Off / Bridge devices only / Everyone for 10 minutes, with the middle one labelled as invisible to Apple devices.

## Consequences
- AirDrop is feature-flagged and never the only way to move files.
- Re-check Tier A after every Quick Share update.
