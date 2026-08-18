# Seeding the knowledge layer

The knowledge corpus ships **empty**, on purpose.

Every entry becomes something IntelSpend states to a user as a tax or regulatory
fact, with a publisher's name attached. Text generated from a model's
recollection would produce authoritative-looking claims about somebody's legal
position that no document actually supports — the precise failure this layer was
built to prevent. A citation makes a wrong claim *more* credible, not less, so
filling this in with invented content would be worse than not building it.

Until real content is loaded, questions about tax or regulation get an honest
"the app does not hold anything current on this" rather than an answer. That is
the correct behaviour for an empty corpus, and it is tested.

## What a reviewer is agreeing to

Putting your name in `reviewer` means you have:

1. Opened the URL yourself.
2. Read the passage you are quoting, in full, in its context.
3. Confirmed the `claim` statements are supported by that passage — not merely
   consistent with it, and not something you believe to be true from elsewhere.
4. Set a `reviewDueDate` by which somebody will check again.

That last point is the one people skip. A source is not permanently true because
it was true when you read it: circulars get superseded, Finance Acts amend
sections, and thresholds change. The loader refuses a review interval over a
year, and retrieval stops serving a source the moment its date passes.

## Where to get content

- **SEBI** — investor education material, regulations, master circulars
  (`sebi.gov.in`, `investor.sebi.gov.in`)
- **RBI** — banking rules, notifications (`rbi.org.in`)
- **Income Tax Department** — the Act, rules, official FAQs
  (`incometaxindia.gov.in`)
- **AMFI**, **NSE**, **BSE** — where the question is about market mechanics and
  the publisher is clearly identified

Secondary sources (news, blogs, aggregators) must not be loaded. They may be
right, but they cannot be cited as authority and their being wrong is not
detectable from here.

## Entry format

```json
{
  "publisher": "Securities and Exchange Board of India",
  "title": "Master Circular for Investment Advisers",
  "url": "https://www.sebi.gov.in/...",
  "jurisdiction": "IN",
  "reviewer": "Shivam",
  "retrievedDate": 1755500000000,
  "effectiveDate": 1739000000000,
  "reviewDueDate": 1787000000000,
  "sourceText": "<the full text you reviewed, used for the change hash>",
  "snippets": [
    {
      "topic": "RISK_PROFILING",
      "text": "<the passage, quoted>",
      "keywords": "risk profiling suitability adviser assessment",
      "effectiveFrom": 1739000000000,
      "effectiveTo": null,
      "claims": [
        {
          "key": "sebi.ia.risk_profiling_required",
          "text": "SEBI requires a registered investment adviser to assess a client's risk profile before advising."
        }
      ]
    }
  ]
}
```

`topic` must be one of the values in `knowledgeRetrieval.TOPIC`. Anything else is
rejected — a free-text topic would make retrieval depend on wording rather than
subject.

## Loading

```bash
node src/scripts/loadKnowledge.js path/to/entry.json
```

The loader validates before touching the database and refuses the whole entry on
any problem. It is deliberately unhelpful about near-misses: an entry that almost
validates should fail, because the cost of a bad one is a wrong claim shown with
a regulator's name beside it.

## The change hash

`sourceText` is hashed on load. `verifySourceHash` re-checks a source against the
text now at its URL; a mismatch flips it to `UNDER_REVIEW` and it stops being
cited until somebody looks.

The hash is never silently updated on mismatch. Updating it would mean the app
starts citing new text for an old claim, which is exactly what the hash exists to
catch.

## Reviewing what is loaded

```bash
node src/scripts/loadKnowledge.js --inventory
```

Lists every source with its reviewer, review date, and whether it is overdue.
