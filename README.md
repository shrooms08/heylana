# Heylana

An on-screen AI buddy for the Solana Seeker: a small glass disc that floats over
every app, answers out loud about what is on screen, points at the button you
need, and walks you through a task one tap at a time. See `PRODUCT.md` for what it
does and what leaves the phone, `CLAUDE.md` for how it is built, and
`worker/README.md` for the proxy that holds the keys.

## Credits

- **Orb states** — the listening, thinking, working and speaking orbs on the disc
  are a Kotlin port of the geometry engine of
  [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs)
  by Jakub Antalik, MIT licence. The licence ships in the app at
  `app/src/main/assets/licenses/thinking-orbs.txt`; the port is tested against the
  library's own golden vectors.
- **Liquid glass reference shader** — `design/refs/liquid_glass.glsl`, from
  [Shadertoy WftXD2](https://www.shadertoy.com/view/WftXD2).
- **The proxy** — shaped after [Farza's Clicky worker](https://github.com/farzaa/clicky) (MIT).
