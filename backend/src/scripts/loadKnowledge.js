#!/usr/bin/env node
/**
 * Loads a reviewed knowledge entry, or lists what is already there.
 *
 * Usage:
 *   node src/scripts/loadKnowledge.js path/to/entry.json
 *   node src/scripts/loadKnowledge.js --inventory
 *
 * See KNOWLEDGE_SEEDING.md. The short version: whoever runs this is putting
 * their name against a claim the app will state to users as regulatory fact.
 */
require('dotenv').config();

const fs = require('fs');
const { loadEntry, inventory } = require('../services/knowledgeLoader');
const { pool } = require('../config/db');

async function main() {
  const argument = process.argv[2];

  if (!argument) {
    console.error('Usage: node src/scripts/loadKnowledge.js <entry.json | --inventory>');
    process.exitCode = 1;
    return;
  }

  if (argument === '--inventory') {
    const sources = await inventory();

    if (sources.length === 0) {
      console.log('Nothing loaded. Questions about tax and regulation will be declined.');
      return;
    }

    for (const source of sources) {
      const due = new Date(source.reviewDueDate).toISOString().slice(0, 10);
      const flag = source.isOverdue ? ' OVERDUE' : '';
      console.log(
        `[${source.status}${flag}] ${source.publisher} — ${source.title}\n` +
        `    reviewer: ${source.reviewer}   review due: ${due}   snippets: ${source.snippetCount}`
      );
    }
    return;
  }

  const entry = JSON.parse(fs.readFileSync(argument, 'utf8'));
  const result = await loadEntry(entry);

  console.log(
    `Loaded source ${result.sourceId} with ${result.snippetCount} snippet(s), ` +
    `reviewed by ${entry.reviewer}.`
  );
}

main()
  .catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  })
  .finally(() => pool.end());
