#!/usr/bin/env node

const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const CHANGELOG = path.resolve(process.cwd(), "CHANGELOG.md");
const DEFAULT_REMOTE = "origin";
const SECTIONS = ["Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"];
const TYPE_TO_SECTION = new Map([
  ["feat", "Added"],
  ["feature", "Added"],
  ["fix", "Fixed"],
  ["bugfix", "Fixed"],
  ["perf", "Changed"],
  ["refactor", "Changed"],
  ["style", "Changed"],
  ["build", "Changed"],
  ["ci", "Changed"],
  ["revert", "Changed"],
  ["docs", "Changed"],
  ["doc", "Changed"],
  ["deprecate", "Deprecated"],
  ["remove", "Removed"],
  ["security", "Security"],
]);
const SKIP_TYPES = new Set(["test", "tests", "chore"]);

function usage() {
  console.log(`Usage:
  node release/generate-changelog.js [options]

Options:
  --version <x.y.z>    Release version to write. Defaults to auto.
  --from-tag <tag>     Start from this tag. Defaults to the latest v* tag.
  --to <rev>           End revision. Defaults to HEAD.
  --dry-run            Print the generated release block without editing CHANGELOG.md.
  --allow-empty        Write an empty release section when no relevant commits are found.
  --no-links           Do not update Keep a Changelog compare links at the bottom.
  --help               Show this help.

Examples:
  ./gradlew releaseChangelog
  ./gradlew releaseChangelog -PreleaseVersion=0.2.3
  node release/generate-changelog.js --dry-run
`);
}

function parseArgs(argv) {
  const args = {
    to: "HEAD",
    dryRun: false,
    allowEmpty: false,
    links: true,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") args.help = true;
    else if (arg === "--dry-run") args.dryRun = true;
    else if (arg === "--allow-empty") args.allowEmpty = true;
    else if (arg === "--no-links") args.links = false;
    else if (arg === "--version") args.version = argv[++i];
    else if (arg === "--from-tag") args.fromTag = argv[++i];
    else if (arg === "--to") args.to = argv[++i];
    else throw new Error(`Unknown argument: ${arg}`);
  }

  return args;
}

function git(args) {
  return execFileSync("git", args, {
    cwd: process.cwd(),
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

function latestVersionTag() {
  const tags = git(["tag", "--list", "v[0-9]*", "--sort=-version:refname"])
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  return tags[0] || null;
}

function parseSemverTag(tag) {
  const match = tag?.match(/^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/);
  if (!match) return null;
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
  };
}

function bumpVersion(fromTag, commits) {
  const current = parseSemverTag(fromTag);
  if (!current) {
    throw new Error("Automatic versioning needs a latest SemVer tag like v0.2.2.");
  }

  const bump = commits.reduce((highest, commit) => {
    if (highest === "major" || commit.bump === "major") return "major";
    if (highest === "minor" || commit.bump === "minor") return "minor";
    return "patch";
  }, "patch");

  if (bump === "major") return `${current.major + 1}.0.0`;
  if (bump === "minor") return `${current.major}.${current.minor + 1}.0`;
  return `${current.major}.${current.minor}.${current.patch + 1}`;
}

function remoteWebUrl() {
  try {
    const raw = git(["remote", "get-url", DEFAULT_REMOTE]);
    const cleaned = raw.replace(/\.git$/, "");
    const ssh = cleaned.match(/^git@github\.com:(.+)$/);
    if (ssh) return `https://github.com/${ssh[1]}`;
    if (cleaned.startsWith("https://github.com/")) return cleaned;
  } catch (_) {
    // Keep the changelog generation useful even outside a fully configured clone.
  }
  return null;
}

function parseCommit(raw) {
  const [hash, subject, body = ""] = raw.split("\x1f");
  const conventional = subject.match(/^([a-zA-Z]+)(?:\(([^)]+)\))?(!)?:\s+(.+)$/);
  const breaking = Boolean(conventional?.[3]) || /BREAKING CHANGE:/i.test(body);

  if (conventional) {
    const type = conventional[1].toLowerCase();
    if (SKIP_TYPES.has(type) && !breaking) return null;

    const scope = conventional[2];
    const section = breaking ? "Changed" : TYPE_TO_SECTION.get(type);
    if (!section) return null;

    const description = conventional[4].trim();
    return {
      hash,
      section,
      bump: breaking ? "major" : type === "feat" || type === "feature" ? "minor" : "patch",
      text: scope ? `${capitalize(scope)}: ${description}` : capitalize(description),
    };
  }

  return {
    hash,
    section: "Changed",
    bump: "patch",
    text: normalizeFallbackSubject(subject),
  };
}

function normalizeFallbackSubject(subject) {
  return subject
    .replace(/^update\s+/i, "Updated ")
    .replace(/^enhance\s+/i, "Enhanced ")
    .replace(/^refactor\s+/i, "Refactored ")
    .replace(/^fix\s+/i, "Fixed ")
    .replace(/^add\s+/i, "Added ")
    .trim();
}

function capitalize(text) {
  if (!text) return text;
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function collectCommits(fromTag, to) {
  const range = fromTag ? `${fromTag}..${to}` : to;
  const output = git([
    "log",
    range,
    "--no-merges",
    "--pretty=format:%h%x1f%s%x1f%b%x1e",
  ]);

  return output
    .split("\x1e")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map(parseCommit)
    .filter(Boolean);
}

function groupCommits(commits) {
  const grouped = new Map(SECTIONS.map((section) => [section, []]));
  for (const commit of commits) {
    grouped.get(commit.section).push(commit);
  }
  return grouped;
}

function releaseBlock(version, date, grouped) {
  const lines = [`## [${version}] - ${date}`, ""];

  for (const section of SECTIONS) {
    const commits = grouped.get(section);
    if (!commits || commits.length === 0) continue;

    lines.push(`### ${section}`);
    for (const commit of commits) {
      lines.push(`- ${commit.text} (${commit.hash})`);
    }
    lines.push("");
  }

  return `${lines.join("\n").trimEnd()}\n`;
}

function insertReleaseBlock(changelog, block) {
  const unreleased = "## [Unreleased]";
  const index = changelog.indexOf(unreleased);
  if (index < 0) {
    throw new Error("CHANGELOG.md must contain a '## [Unreleased]' section.");
  }

  const afterUnreleased = index + unreleased.length;
  const before = changelog.slice(0, afterUnreleased);
  const after = changelog.slice(afterUnreleased).replace(/^\s*/, "\n\n");
  return `${before}\n\n${block}${after}`;
}

function upsertLinks(changelog, version, fromTag, remoteUrl) {
  if (!remoteUrl) return changelog;

  const versionTag = `v${version}`;
  const unreleasedLink = `[Unreleased]: ${remoteUrl}/compare/${versionTag}...HEAD`;
  const versionLink = fromTag
    ? `[${version}]: ${remoteUrl}/compare/${fromTag}...${versionTag}`
    : `[${version}]: ${remoteUrl}/releases/tag/${versionTag}`;

  const lines = changelog.split("\n");
  const withoutExisting = lines.filter(
    (line) => !line.startsWith("[Unreleased]: ") && !line.startsWith(`[${version}]: `),
  );

  while (withoutExisting.length > 0 && withoutExisting[withoutExisting.length - 1] === "") {
    withoutExisting.pop();
  }

  return `${withoutExisting.join("\n")}\n\n${unreleasedLink}\n${versionLink}\n`;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    usage();
    return;
  }
  if (!fs.existsSync(CHANGELOG)) {
    throw new Error(`CHANGELOG.md not found at ${CHANGELOG}`);
  }

  const fromTag = args.fromTag || latestVersionTag();
  const commits = collectCommits(fromTag, args.to);
  if (commits.length === 0 && !args.allowEmpty) {
    throw new Error(`No changelog-worthy commits found in ${fromTag || "repo"}..${args.to}.`);
  }

  const version = args.version && args.version !== "auto" ? args.version : bumpVersion(fromTag, commits);
  if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(version)) {
    throw new Error(`Version must look like SemVer without leading 'v': ${version}`);
  }

  const today = new Date().toISOString().slice(0, 10);
  const block = releaseBlock(version, today, groupCommits(commits));

  if (args.dryRun) {
    console.log(block);
    return;
  }

  const current = fs.readFileSync(CHANGELOG, "utf8");
  let next = insertReleaseBlock(current, block);
  if (args.links) {
    next = upsertLinks(next, version, fromTag, remoteWebUrl());
  }
  fs.writeFileSync(CHANGELOG, next);
  console.log(`Updated CHANGELOG.md with v${version} from ${fromTag || "initial history"}..${args.to}`);
}

try {
  main();
} catch (error) {
  console.error(`generate-changelog: ${error.message}`);
  process.exit(1);
}
