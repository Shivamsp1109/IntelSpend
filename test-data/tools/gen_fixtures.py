"""Generates the ingestion fixture corpus for Spendwise.

Statement fixtures carry real word coordinates laid out like an A4 bank statement:
left-aligned date and narration, right-aligned money columns. That geometry is the whole
point -- it is what the extractor has to reason about.
"""
import json
import os
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
TD = os.path.join(ROOT, "test-data")

CHAR_W = 4.6
FONT_H = 9.0

# Column geometry (A4 points)
X_DATE = 40.0
X_NARR = 100.0
X_REF = 300.0
R_DEBIT = 430.0
R_CREDIT = 496.0
R_BAL = 566.0
R_AMOUNT = 460.0


def w(text, left, top, right=None, bottom=None, page=1):
    if right is None:
        right = left + len(text) * CHAR_W
    if bottom is None:
        bottom = top + FONT_H
    return {"text": text, "left": round(left, 2), "top": round(top, 2),
            "right": round(right, 2), "bottom": round(bottom, 2), "page": page}


def left_words(text, x, y, page=1):
    out = []
    cur = x
    for tok in text.split():
        out.append(w(tok, cur, y, page=page))
        cur += (len(tok) + 1) * CHAR_W
    return out


def right_word(text, right_edge, y, page=1):
    return w(text, right_edge - len(text) * CHAR_W, y, right=right_edge, page=page)


def money(v):
    return f"{v:,.2f}"


def merchant_token(narr):
    """The payee we expect the title to contain.

    Bank narrations read CHANNEL/MERCHANT/LOCATION, so the payee is the second segment.
    """
    if "/" in narr:
        return narr.split("/")[1].split()[0]
    return narr.split()[0]


def write_fixture(folder, name, words, expected, note, full_text=None):
    os.makedirs(os.path.join(TD, folder), exist_ok=True)
    if full_text is None:
        # Approximate what a text extractor would emit, grouped by row.
        rows = {}
        for wd in words:
            rows.setdefault((wd["page"], round(wd["top"] / 4)), []).append(wd)
        lines = []
        for key in sorted(rows):
            lines.append(" ".join(x["text"] for x in sorted(rows[key], key=lambda z: z["left"])))
        full_text = "\n".join(lines)
    payload = {"note": note, "fullText": full_text, "words": words, "expected": expected}
    path = os.path.join(TD, folder, name + ".fixture.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=1)
    print("wrote", os.path.relpath(path, ROOT), f"({len(expected)} expected txns)")


# ---------------------------------------------------------------- statements

def build_statement(name, note, rows, headers=True, include_balance=True,
                    single_amount=False, opening=100000.0, jitter=0.0,
                    date_fmt="{d:02d}/{m:02d}/{y}"):
    """rows: list of (day, month, year, narration, amount, is_credit)"""
    words = []
    y = 90.0
    if headers:
        words += left_words("Date", X_DATE, y)
        words += left_words("Narration", X_NARR, y)
        words += left_words("Chq/Ref No", X_REF, y)
        if single_amount:
            words.append(right_word("Amount", R_AMOUNT, y))
        else:
            words.append(right_word("Withdrawal", R_DEBIT, y))
            words.append(right_word("Deposit", R_CREDIT, y))
        if include_balance:
            words.append(right_word("Balance", R_BAL, y))
        y += 20.0

    balance = opening
    expected = []
    for i, (d, m, yr, narr, amt, is_credit) in enumerate(rows):
        ry = y + (jitter if i % 2 == 0 else -jitter)
        datestr = date_fmt.format(d=d, m=m, y=yr)
        words.append(w(datestr, X_DATE, ry))
        words += left_words(narr, X_NARR, ry)
        words.append(w(f"REF{100000 + i * 7}", X_REF, ry))

        if single_amount:
            suffix = "Cr" if is_credit else "Dr"
            words.append(right_word(money(amt), R_AMOUNT - 14, ry))
            words.append(w(suffix, R_AMOUNT - 10, ry))
        else:
            words.append(right_word(money(amt), R_CREDIT if is_credit else R_DEBIT, ry))

        balance = balance + amt if is_credit else balance - amt
        if include_balance:
            words.append(right_word(money(balance), R_BAL, ry))

        expected.append({
            "date": f"{yr if yr > 99 else 2000 + yr}-{m:02d}-{d:02d}",
            "amount": amt,
            "type": "CREDIT" if is_credit else "DEBIT",
            "merchantContains": merchant_token(narr),
        })
        y += 16.0

    write_fixture("statements", name, words, expected, note)


ROWS_A = [
    (12, 1, 2026, "UPI/AMAZON PAY/9876543210/Order", 1299.00, False),
    (14, 1, 2026, "POS/SWIGGY/BANGALORE", 456.50, False),
    (15, 1, 2026, "NEFT/ACME PAYROLL/SALARY JAN", 85000.00, True),
    (18, 1, 2026, "UPI/UBER INDIA/trip fare", 289.00, False),
    (21, 1, 2026, "POS/RELIANCE FRESH/MUMBAI", 2340.75, False),
    (23, 1, 2026, "IMPS/RAHUL SHARMA/rent share", 7500.00, True),
    (25, 1, 2026, "UPI/BESCOM/electricity bill", 1875.00, False),
    (28, 1, 2026, "POS/CROMA/headphones", 8999.00, False),
]

build_statement(
    "hdfc_style_withdrawal_deposit_balance",
    "Five-column statement with separate Withdrawal/Deposit columns and a running balance. "
    "Direction is verifiable from the balance movement.",
    ROWS_A,
)

build_statement(
    "single_amount_column_dr_cr_suffix",
    "One Amount column with Dr/Cr suffixes plus a balance column.",
    ROWS_A[:6],
    single_amount=True,
)

build_statement(
    "no_balance_column",
    "No balance column, so direction must come from which money column the figure sits in.",
    ROWS_A,
    include_balance=False,
)

build_statement(
    "no_header_row",
    "Header row absent entirely - columns must be recovered by clustering the right edges "
    "of the figures.",
    ROWS_A,
    headers=False,
)

build_statement(
    "ocr_baseline_jitter",
    "Simulates OCR of a scanned statement: each row's baseline wobbles, which used to split "
    "one transaction across several lines and drop it.",
    ROWS_A,
    jitter=2.5,
)

build_statement(
    "two_digit_year",
    "dd/MM/yy dates.",
    [(d, m, 26, n, a, c) for (d, m, _, n, a, c) in ROWS_A[:5]],
    date_fmt="{d:02d}/{m:02d}/{y:02d}",
)

# US-style MM/DD/YYYY. 01/15 is unambiguous and settles the order for the whole document,
# which is what lets 01/05 be read correctly as 5 January.
US_ROWS = [
    (15, 1, 2026, "AMAZON MKTPLACE", 42.99, False),
    (5, 1, 2026, "STARBUCKS STORE 411", 8.75, False),
    (22, 1, 2026, "ACME CORP DIRECT DEP", 3200.00, True),
    (9, 2, 2026, "SHELL OIL 8842", 55.20, False),
    (11, 2, 2026, "WHOLE FOODS MKT", 132.44, False),
]
build_statement(
    "us_month_first_dates",
    "MM/DD/YYYY document. Parsing each date in isolation reads 01/05 as 1 May; resolving the "
    "order across the whole file reads it as 5 January.",
    US_ROWS,
    date_fmt="{m:02d}/{d:02d}/{y}",
)


def build_wrapped_statement():
    """Narration wraps onto a second row that carries no date and no figures."""
    words = []
    y = 90.0
    words += left_words("Date", X_DATE, y)
    words += left_words("Particulars", X_NARR, y)
    words.append(right_word("Withdrawal", R_DEBIT, y))
    words.append(right_word("Deposit", R_CREDIT, y))
    words.append(right_word("Balance", R_BAL, y))
    y += 20.0

    rows = [
        (12, 1, "UPI/FLIPKART INTERNET/order", "confirmation 4471829", 3499.00, False),
        (16, 1, "NEFT/GLOBEX SOLUTIONS/consulting", "invoice for December", 45000.00, True),
        (19, 1, "POS/BIG BAZAAR/monthly", "grocery run", 4210.50, False),
        (24, 1, "UPI/AIRTEL PREPAID/mobile", "recharge plan", 719.00, False),
    ]
    balance = 100000.0
    expected = []
    for i, (d, m, narr, cont, amt, is_credit) in enumerate(rows):
        words.append(w(f"{d:02d}/{m:02d}/2026", X_DATE, y))
        words += left_words(narr, X_NARR, y)
        words.append(right_word(money(amt), R_CREDIT if is_credit else R_DEBIT, y))
        balance = balance + amt if is_credit else balance - amt
        words.append(right_word(money(balance), R_BAL, y))
        y += 12.0
        words += left_words(cont, X_NARR, y)
        y += 16.0
        expected.append({
            "date": f"2026-{m:02d}-{d:02d}",
            "amount": amt,
            "type": "CREDIT" if is_credit else "DEBIT",
            "merchantContains": narr.split("/")[1].split()[0],
        })

    write_fixture("statements", "wrapped_narration", words, expected,
                  "Narration wraps to a continuation row with no date and no figures. Those rows "
                  "must attach to the transaction above, not be dropped or parsed as their own.")


build_wrapped_statement()


def build_noise_statement():
    """Real statements are full of page furniture that must not become transactions."""
    words = []
    y = 40.0
    words += left_words("HDFC BANK LIMITED", 40.0, y); y += 12
    words += left_words("Statement Period 01/01/2026 to 31/01/2026", 40.0, y); y += 12
    words += left_words("Account Number 50100123456789 IFSC HDFC0001234", 40.0, y); y += 12
    words += left_words("Opening Balance 100,000.00", 40.0, y); y += 20

    words += left_words("Date", X_DATE, y)
    words += left_words("Narration", X_NARR, y)
    words.append(right_word("Withdrawal", R_DEBIT, y))
    words.append(right_word("Deposit", R_CREDIT, y))
    words.append(right_word("Balance", R_BAL, y))
    y += 20

    balance = 100000.0
    expected = []
    for (d, m, yr, narr, amt, is_credit) in ROWS_A[:5]:
        words.append(w(f"{d:02d}/{m:02d}/{yr}", X_DATE, y))
        words += left_words(narr, X_NARR, y)
        words.append(right_word(money(amt), R_CREDIT if is_credit else R_DEBIT, y))
        balance = balance + amt if is_credit else balance - amt
        words.append(right_word(money(balance), R_BAL, y))
        expected.append({
            "date": f"{yr}-{m:02d}-{d:02d}",
            "amount": amt,
            "type": "CREDIT" if is_credit else "DEBIT",
            "merchantContains": narr.split("/")[1].split()[0],
        })
        y += 16

    y += 10
    words += left_words("Closing Balance 171,528.75", 40.0, y); y += 12
    words += left_words("Total Debits 6,360.50 Total Credits 85,000.00", 40.0, y); y += 12
    words += left_words("Page 1 of 1", 40.0, y); y += 12
    words += left_words("This is a computer generated statement 31/01/2026", 40.0, y)

    write_fixture("statements", "with_page_furniture", words, expected,
                  "Header block, opening/closing balance lines, totals and a footer. None of "
                  "these are transactions.")


build_noise_statement()

# ---------------------------------------------------------------- csv

def write_csv(name, content, expected, note, encoding="utf-8"):
    os.makedirs(os.path.join(TD, "csv"), exist_ok=True)
    with open(os.path.join(TD, "csv", name), "w", encoding=encoding, newline="") as f:
        f.write(content)
    with open(os.path.join(TD, "csv", name + ".expected.json"), "w", encoding="utf-8") as f:
        json.dump({"note": note, "expected": expected}, f, indent=1)
    print("wrote", "test-data/csv/" + name, f"({len(expected)} expected txns)")


write_csv(
    "debit_credit_amount_headers.csv",
    "Txn Date,Value Date,Description,Debit Amount,Credit Amount,Balance\n"
    "12/01/2026,12/01/2026,UPI/AMAZON PAY/order,1299.00,,98701.00\n"
    "14/01/2026,14/01/2026,POS/SWIGGY/BANGALORE,456.50,,98244.50\n"
    "15/01/2026,15/01/2026,NEFT/ACME PAYROLL/SALARY,,85000.00,183244.50\n"
    "18/01/2026,18/01/2026,UPI/UBER INDIA/trip,289.00,,182955.50\n"
    "21/01/2026,21/01/2026,POS/RELIANCE FRESH,2340.75,,180614.75\n",
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-14", "amount": 456.50, "type": "DEBIT", "merchantContains": "Swiggy"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
        {"date": "2026-01-18", "amount": 289.00, "type": "DEBIT", "merchantContains": "Uber"},
        {"date": "2026-01-21", "amount": 2340.75, "type": "DEBIT", "merchantContains": "Reliance"},
    ],
    "'Debit Amount' and 'Credit Amount' both contain the word 'amount'. Mapping them to a "
    "single generic amount column loses the debit side entirely.",
)

write_csv(
    "preamble_rows.csv",
    "HDFC Bank Limited\n"
    "Account Statement\n"
    "Account Number:,50100123456789\n"
    "Period:,01-Jan-2026 to 31-Jan-2026\n"
    "Currency:,INR\n"
    "\n"
    "Value Dt,Narration,Withdrawal Amt,Deposit Amt,Closing Balance\n"
    "12-Jan-2026,UPI/AMAZON PAY/order,1299.00,,98701.00\n"
    "15-Jan-2026,NEFT/ACME PAYROLL/SALARY,,85000.00,183701.00\n"
    "21-Jan-2026,POS/RELIANCE FRESH,2340.75,,181360.25\n",
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
        {"date": "2026-01-21", "amount": 2340.75, "type": "DEBIT", "merchantContains": "Reliance"},
    ],
    "Five preamble rows before the real header. Falling back to row 0 as the header maps no "
    "columns and yields nothing.",
)

write_csv(
    "semicolon_european_decimals.csv",
    "Datum;Beschreibung;Betrag;Saldo\n"
    "12.01.2026;AMAZON EU SARL;-1.299,00;98.701,00\n"
    "15.01.2026;LOHN ACME GMBH;85.000,00;183.701,00\n"
    "21.01.2026;REWE MARKT;-2.340,75;181.360,25\n",
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Lohn"},
        {"date": "2026-01-21", "amount": 2340.75, "type": "DEBIT", "merchantContains": "Rewe"},
    ],
    "Semicolon delimited with European decimal commas and dotted dates.",
)

write_csv(
    "unsigned_amount_with_balance.csv",
    "Date,Particulars,Amount,Balance\n"
    "12/01/2026,UPI/AMAZON PAY/order,1299.00,98701.00\n"
    "15/01/2026,NEFT/ACME PAYROLL/SALARY,85000.00,183701.00\n"
    "18/01/2026,UPI/UBER INDIA/trip,289.00,183412.00\n"
    "23/01/2026,IMPS/RAHUL SHARMA/rent,7500.00,190912.00\n",
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
        {"date": "2026-01-18", "amount": 289.00, "type": "DEBIT", "merchantContains": "Uber"},
        {"date": "2026-01-23", "amount": 7500.00, "type": "CREDIT", "merchantContains": "Rahul"},
    ],
    "Unsigned single amount column. Only the balance movement reveals which rows are credits.",
)

write_csv(
    "dr_cr_indicator_column.csv",
    "Tran Date\tDescription\tAmount\tDr/Cr\tBalance\n"
    "12/01/2026\tAMAZON PAY ORDER\t1299.00\tDR\t98701.00\n"
    "15/01/2026\tACME PAYROLL SALARY\t85000.00\tCR\t183701.00\n"
    "21/01/2026\tRELIANCE FRESH MUMBAI\t2340.75\tDR\t181360.25\n",
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
        {"date": "2026-01-21", "amount": 2340.75, "type": "DEBIT", "merchantContains": "Reliance"},
    ],
    "Tab separated, with a separate Dr/Cr indicator column.",
)

write_csv(
    "utf16_bom.csv",
    "Date,Description,Amount\n"
    "12/01/2026,AMAZON PAY,-1299.00\n"
    "15/01/2026,ACME SALARY,85000.00\n",
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
    ],
    "UTF-16LE with a BOM, which is what Excel's 'Unicode Text' export produces.",
    encoding="utf-16",
)

# ---------------------------------------------------------------- xlsx

def write_xlsx(name, header, rows, expected, note, date_style=True):
    from openpyxl import Workbook
    os.makedirs(os.path.join(TD, "xlsx"), exist_ok=True)
    wb = Workbook()
    ws = wb.active
    ws.append(header)
    for r in rows:
        ws.append(r)
    if date_style:
        for row in ws.iter_rows(min_row=2, min_col=1, max_col=1):
            for cell in row:
                cell.number_format = "DD/MM/YYYY"
    path = os.path.join(TD, "xlsx", name)
    wb.save(path)
    with open(os.path.join(TD, "xlsx", name + ".expected.json"), "w", encoding="utf-8") as f:
        json.dump({"note": note, "expected": expected}, f, indent=1)
    print("wrote", "test-data/xlsx/" + name, f"({len(expected)} expected txns)")


import datetime as _dt

write_xlsx(
    "bank_export.xlsx",
    ["Txn Date", "Narration", "Debit", "Credit", "Balance"],
    [
        [_dt.date(2026, 1, 12), "UPI/AMAZON PAY/order", 1299.00, None, 98701.00],
        [_dt.date(2026, 1, 15), "NEFT/ACME PAYROLL/SALARY", None, 85000.00, 183701.00],
        [_dt.date(2026, 1, 18), "UPI/UBER INDIA/trip", 289.00, None, 183412.00],
        [_dt.date(2026, 1, 21), "POS/RELIANCE FRESH", 2340.75, None, 181071.25],
    ],
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
        {"date": "2026-01-18", "amount": 289.00, "type": "DEBIT", "merchantContains": "Uber"},
        {"date": "2026-01-21", "amount": 2340.75, "type": "DEBIT", "merchantContains": "Reliance"},
    ],
    "Real .xlsx with genuine date-formatted cells, which arrive as bare day-count serials.",
)

write_xlsx(
    "sparse_columns.xlsx",
    ["Date", "Description", "Category", "Amount"],
    [
        [_dt.date(2026, 1, 12), "AMAZON PAY", None, -1299.00],
        [_dt.date(2026, 1, 15), "ACME SALARY", None, 85000.00],
        [_dt.date(2026, 1, 18), "UBER INDIA", "Travel", -289.00],
    ],
    [
        {"date": "2026-01-12", "amount": 1299.00, "type": "DEBIT", "merchantContains": "Amazon"},
        {"date": "2026-01-15", "amount": 85000.00, "type": "CREDIT", "merchantContains": "Acme"},
        {"date": "2026-01-18", "amount": 289.00, "type": "DEBIT", "merchantContains": "Uber"},
    ],
    "Empty cells in the middle of rows. Excel omits them, so reading cells sequentially "
    "shifts every later column.",
)

print("\ndone")
