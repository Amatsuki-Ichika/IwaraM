// Directories that are never repository documentation: VCS metadata, IDE and
// tool caches, build output and dependency stores. Both Markdown gates import
// this one set so the link and hygiene checks always agree on what they walk;
// a directory missing here is a gate walk, not a decision by one script.
export const MD_SKIP_DIRS = new Set([
  ".git",
  ".gradle",
  ".kotlin",
  ".idea",
  "build",
  "node_modules",
  ".zcode",
  "dist",
  ".local",
  "out",
  "coverage",
  ".venv",
  "__pycache__",
  ".artifacts",
]);
