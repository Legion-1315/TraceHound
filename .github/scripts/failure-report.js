// Posts a compact failure summary as a commit comment: failing tests with their messages,
// compiler errors, and the tail of each step log. Lets a reviewer (or an agent without
// access to the Actions log API) see why the run failed.
const fs = require('fs');
const path = require('path');

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === '.git') continue;
      walk(p, out);
    } else out.push(p);
  }
  return out;
}

function testFailures() {
  const out = [];
  for (const f of walk('.').filter((p) => /build\/test-results\/.*\.xml$/.test(p))) {
    const xml = fs.readFileSync(f, 'utf8');
    const re = /<testcase name="([^"]*)" classname="([^"]*)"[^>]*>\s*<failure message="([^"]*)"[^>]*>([\s\S]*?)<\/failure>/g;
    let m;
    while ((m = re.exec(xml)) !== null) {
      out.push(`- **${m[2]}.${m[1]}**: ${m[3].slice(0, 400)}\n\`\`\`\n${m[4].slice(0, 1500)}\n\`\`\``);
    }
  }
  return out;
}

function logTails() {
  const out = [];
  for (const f of fs.readdirSync('.').filter((n) => /^ci-.*\.log$/.test(n))) {
    const lines = fs.readFileSync(f, 'utf8').split('\n');
    const errors = lines.filter((l) => /error:|FAILED|Exception|BUILD FAILED|FAIL:|✗/.test(l)).slice(0, 60);
    const tail = lines.slice(-80);
    out.push(`### ${f}\nKey lines:\n\`\`\`\n${errors.join('\n').slice(0, 6000)}\n\`\`\`\nTail:\n\`\`\`\n${tail.join('\n').slice(-8000)}\n\`\`\``);
  }
  return out;
}

module.exports = async ({ github, context, job }) => {
  const body = [
    `CI **${job}** job failed for ${context.sha.slice(0, 7)}.`,
    '',
    '## Failing tests',
    ...(testFailures().slice(0, 15)),
    '',
    '## Logs',
    ...logTails(),
  ].join('\n').slice(0, 60000);
  await github.rest.repos.createCommitComment({
    owner: context.repo.owner,
    repo: context.repo.repo,
    commit_sha: context.sha,
    body,
  });
};
