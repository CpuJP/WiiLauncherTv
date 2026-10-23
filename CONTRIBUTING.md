# Contributing

Thanks for helping! Keep the launcher **light**: no new dependencies unless there is no other way, and nothing that runs while the menu is not on screen.

1. Fork the repository and create a branch.
2. Make your change. Match the existing style (plain Java, no AndroidX, everything drawn in `WiiMenuView`).
3. Add or update unit tests in `app/src/test` for any new logic.
4. Run `./gradlew test lintRelease assembleRelease`. CI runs the same command.
5. If you have a TV, try it with `tools/deploy.sh` (set `TV=<ip>:5555`).
6. Open a pull request that explains what changed and how you tested it.

Never add Nintendo assets (images, sounds, fonts). See [DISCLAIMER.md](DISCLAIMER.md).
