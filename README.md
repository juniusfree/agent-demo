# 🤧🦠 If you’re allergic to vibe-coded apps, turn away immediately.

*The owner hasn’t even reviewed the code and doesn’t know how it works internally.*

---

**Agent Demo lets coding agents turn finished browser features into narrated demo videos that help reviewers understand what changed.**

https://github.com/user-attachments/assets/89743701-cc1f-4494-a347-4960adbc80f3

*55-second demo. Turn on sound for narration.*

## Give this to your agent

You describe what to demonstrate. Your agent handles setup, browser actions,
narration, captions, camera framing, and editing.

> Use https://github.com/juniusfree/agent-demo to create a narrated browser
> demo of **[feature]** at **[app URL]**.
>
> Read `docs/agent-guide.md` and `docs/project-format.md` in that repository.
> Reuse an existing checkout or clone it, follow the setup checklist, and
> verify the bundled fixture first. Ask before installing missing software or
> downloading models, and confirm safe demo data before changing the app.
>
> Use the supplied CLI and EDN format—not custom recording helpers.
> Review the output and report the video path and anything you could not verify.

**Prototype:** local, Clojure-based, and browser-only. This helps explain
finished work; it does not prove correctness. Keep raw recordings and
credentials private, and review the exported video before sharing.

[Agent guide](docs/agent-guide.md) · [Project format](docs/project-format.md)
