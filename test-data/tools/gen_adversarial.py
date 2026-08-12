"""Adversarial fixtures: cases the extractor was NOT designed around."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_fixtures import (w, left_words, right_word, money, write_fixture,
                          X_DATE, X_NARR, X_REF, R_DEBIT, R_CREDIT, R_BAL, R_AMOUNT, TD)


def statement_rows(rows, headers, include_balance=True, opening=100000.0,
                   amount_fmt=money, page=1, y_start=90.0, ref_numeric=False,
                   date_fmt="{d:02d}/{m:02d}/{y}", single_amount=False):
    words = []
    y = y_start
    words += left_words("Date", X_DATE, y, page)
    words += left_words("Narration", X_NARR, y, page)
    if ref_numeric:
        words += left_words("Cheque No", X_REF, y, page)
    if single_amount:
        words.append(right_word("Amount", R_AMOUNT, y, page))
    else:
        words.append(right_word("Withdrawal", R_DEBIT, y, page))
        words.append(right_word("Deposit", R_CREDIT, y, page))
    if include_balance:
        words.append(right_word("Balance", R_BAL, y, page))
    y += 20.0

    balance = opening
    expected = []
    for i, (d, m, yr, narr, amt, is_credit) in enumerate(rows):
        words.append(w(date_fmt.format(d=d, m=m, y=yr), X_DATE, y, page=page))
        words += left_words(narr, X_NARR, y, page)
        if ref_numeric:
            words.append(w(str(400123 + i * 3), X_REF, y, page=page))
        if single_amount:
            words.append(right_word(amount_fmt(amt), R_AMOUNT - 14, y, page))
            words.append(w("Cr" if is_credit else "Dr", R_AMOUNT - 10, y, page=page))
        else:
            words.append(right_word(amount_fmt(amt), R_CREDIT if is_credit else R_DEBIT, y, page))
        balance = balance + amt if is_credit else balance - amt
        if include_balance:
            words.append(right_word(amount_fmt(balance), R_BAL, y, page))
        expected.append({
            "date": f"{yr}-{m:02d}-{d:02d}",
            "amount": amt,
            "type": "CREDIT" if is_credit else "DEBIT",
            "merchantContains": narr.split("/")[1].split()[0] if "/" in narr else narr.split()[0],
        })
        y += 16.0
    return words, expected, y


BASE = [
    (12, 1, 2026, "UPI/AMAZON PAY/order", 1299.00, False),
    (14, 1, 2026, "POS/SWIGGY/BANGALORE", 456.50, False),
    (15, 1, 2026, "NEFT/ACME PAYROLL/SALARY", 85000.00, True),
    (18, 1, 2026, "UPI/UBER INDIA/trip", 289.00, False),
    (21, 1, 2026, "POS/RELIANCE FRESH/MUMBAI", 2340.75, False),
]

# 1. Whole-rupee amounts with no decimal point anywhere, plus a numeric cheque-number column
# that is itself a plausible-looking figure.
INT_ROWS = [(d, m, y, n, float(int(a)), c) for (d, m, y, n, a, c) in BASE]
words, expected, _ = statement_rows(INT_ROWS, None, amount_fmt=lambda v: f"{int(v):,}", ref_numeric=True)
write_fixture("statements", "integer_amounts_and_numeric_ref", words, expected,
              "No decimal points anywhere, and a numeric cheque-number column that looks like "
              "money. Nothing distinguishes an amount from a reference by shape alone.")

# 2. Currency symbol fused to every figure.
words, expected, _ = statement_rows(BASE, None, amount_fmt=lambda v: "₹" + money(v))
write_fixture("statements", "currency_symbol_attached", words, expected,
              "Every figure carries a rupee sign with no separating space.")

# 3. The same merchant, amount and date twice in a row - a genuine repeat purchase.
DUP = [
    (12, 1, 2026, "UPI/AMAZON PAY/order", 1299.00, False),
    (12, 1, 2026, "UPI/AMAZON PAY/order", 1299.00, False),
    (13, 1, 2026, "POS/SWIGGY/BANGALORE", 456.50, False),
]
words, expected, _ = statement_rows(DUP, None)
write_fixture("statements", "identical_repeat_transactions", words, expected,
              "Two identical transactions on the same day. Both are real and both must survive.")

# 4. Account goes overdrawn, so the running balance turns negative mid-statement.
OD = [
    (12, 1, 2026, "UPI/AMAZON PAY/order", 1299.00, False),
    (14, 1, 2026, "POS/CROMA/television", 62000.00, False),
    (16, 1, 2026, "NEFT/ACME PAYROLL/SALARY", 85000.00, True),
]
words, expected, _ = statement_rows(OD, None, opening=50000.0)
write_fixture("statements", "overdraft_negative_balance", words, expected,
              "The running balance goes negative. Reading balances as magnitudes breaks the "
              "chain exactly where the account is overdrawn.")

# 5. Two pages, each with its own repeated header block.
w1, e1, _ = statement_rows(BASE[:3], None, page=1)
w2, e2, _ = statement_rows(BASE[3:], None, page=2, opening=100000.0 - 1299.00 - 456.50 + 85000.00)
write_fixture("statements", "multi_page_repeated_header", w1 + w2, e1 + e2,
              "Two pages, header repeated on each. Rows must not merge across the page break.")

# 6. Credit-card style: no balance column, "Cr" only on refunds, month-name dates.
CARD = [
    (12, 1, 2026, "AMAZON RETAIL INDIA", 1299.00, False),
    (14, 1, 2026, "SWIGGY BANGALORE", 456.50, False),
    (17, 1, 2026, "AMAZON RETAIL REFUND", 1299.00, True),
    (21, 1, 2026, "INDIAN OIL PETROL PUMP", 2000.00, False),
]
words, expected, _ = statement_rows(
    CARD, None, include_balance=False, single_amount=True,
    date_fmt="{d:02d} Jan {y}",
)
write_fixture("statements", "credit_card_no_balance", words, expected,
              "Credit-card statement: one amount column, no balance, direction carried only by "
              "a Cr suffix on refunds.")

# ---------------------------------------------------------------- csv


def write_csv(name, content, expected, note, encoding="utf-8"):
    os.makedirs(os.path.join(TD, "csv"), exist_ok=True)
    with open(os.path.join(TD, "csv", name), "w", encoding=encoding, newline="") as f:
        f.write(content)
    with open(os.path.join(TD, "csv", name + ".expected.json"), "w", encoding="utf-8") as f:
        json.dump({"note": note, "expected": expected}, f, indent=1)
    print("wrote test-data/csv/" + name, f"({len(expected)} expected txns)")


write_csv(
    "quoted_delimiter_and_total_row.csv",
    'Date,Description,Amount,Balance\n'
    '12/01/2026,"AMAZON PAY, MUMBAI",1299.00,98701.00\n'
    '15/01/2026,"ACME PAYROLL, SALARY JAN",85000.00,183701.00\n'
    '21/01/2026,"RELIANCE FRESH, MUMBAI",2340.75,181360.25\n'
    'Total,,88639.75,\n',
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
        {"date": "2026-01-21", "amount": 2340.75, "type": "DEBIT", "merchantContains": "Reliance"},
    ],
    "Commas inside quoted descriptions, and a summary Total row at the bottom that is not a "
    "transaction.",
)

write_csv(
    "date_only_ambiguous.csv",
    "Date,Description,Amount\n"
    "01/02/2026,AMAZON PAY,-1299.00\n"
    "03/02/2026,SWIGGY BANGALORE,-456.50\n"
    "05/02/2026,ACME SALARY,85000.00\n",
    [
        {"amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"amount": 456.50, "type": "DEBIT", "merchantContains": "Swiggy"},
        {"amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
    ],
    "Every date is ambiguous - no component exceeds 12 anywhere in the file. Amounts and "
    "directions must still be right; the date is asserted only for parseability.",
)

print("\ndone")
