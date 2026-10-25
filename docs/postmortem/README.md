# Postmortems

A postmortem is a reference for one incident: what happened, in what order, with what evidence,
and what prevents the next one. It is the only place where a narrative belongs.

Write one when all three hold:

- the failure was subtle enough that a reasonable person missed it;
- the reason it escaped is a gap in tests, tooling or conventions, not a one-off typo;
- rediscovering it would cost real time.

Name the file `docs/postmortem/NNNN-short-slug.md` and keep the order chronological: timestamps,
commands, log excerpts and links are evidence; hindsight goes in the last section.

```markdown
# NNNN — <what broke, in one line>

Date: YYYY-MM-DD
Release: <version or rc tag>

## Impact

<Who was affected and what they could not do. State the blast radius, not the severity label.>

## Timeline

- HH:MM <event, with the command or log line that proves it>
- HH:MM <next event>

## Cause

<The mechanism, stated so that someone who was not there can verify it against the code.>

## Why it escaped

<Which check would have caught it, and why that check did not exist or did not fire.>

## Prevention

- <What changed: test, gate, convention or decision record — with the path.>
- <What would still not be caught, stated honestly.>
```

The last section is the contract: a postmortem whose prevention items are not landed is an
unfinished postmortem. A new gate introduced here also needs its failure message and expected cost,
like any other gate.
