#!/usr/bin/env python3
"""Write a minimal but real Anki `.apkg`, for smoke-testing `loopky import`.

A real zip around a real SQLite collection, because the thing under test is the *shipped* read
path — `java.util.zip`, the JDBC driver and the native SQLite library it extracts at runtime — and
none of that is exercised by anything else the binary does. In a `native-image` build that library
is loaded reflectively out of the executable's own resources, which is the one shape a closed-world
image has to be told about by hand (see Architecture.md §13.11): a missing registration is a
runtime failure with a green build behind it, exactly like JNA's.

Deliberately a script rather than a checked-in blob, so the fixture can be read and changed. Needs
nothing but a Python 3 with `sqlite3`, which every CI image has.

    python3 cli/tools/make-sample-apkg.py /tmp/sample.apkg
    loopky import /tmp/sample.apkg --dry-run --json

The deck is shaped like the failure that motivated the field picker: its first field is a column of
database ids, so `chooseDefaultFields` has to skip it. `--dry-run` should report front field 2.
"""

import json
import os
import sqlite3
import sys
import tempfile
import zipfile

# Anki joins a note's fields with the ASCII unit separator.
US = "\x1f"

NOTES = [
    ("2528426", "Hola", "Hello"),
    ("2760065", "Adiós", "Goodbye"),
    ("2760066", "Gracias", "Thank you"),
]


def write_collection(path):
    db = sqlite3.connect(path)
    db.executescript(
        """
        CREATE TABLE notes (id INTEGER PRIMARY KEY, guid TEXT, mid INTEGER, mod INTEGER,
                            usn INTEGER, tags TEXT, flds TEXT, sfld TEXT, csum INTEGER,
                            flags INTEGER, data TEXT);
        CREATE TABLE fields (ntid INTEGER, ord INTEGER, name TEXT);
        CREATE TABLE templates (ntid INTEGER, ord INTEGER, name TEXT);
        CREATE TABLE decks (id INTEGER PRIMARY KEY, name TEXT, kind BLOB);
        """
    )
    for ord_, name in enumerate(["SentenceId", "Spanish", "English"]):
        db.execute("INSERT INTO fields VALUES (1, ?, ?)", (ord_, name))
    db.execute("INSERT INTO templates VALUES (1, 0, 'Card 1')")
    # id 1 is Anki's Default deck, which the reader skips.
    db.execute("INSERT INTO decks VALUES (2, 'Spanish Sentences', NULL)")
    for index, fields in enumerate(NOTES):
        db.execute(
            "INSERT INTO notes VALUES (?, ?, 1, 0, 0, 'spanish', ?, ?, 0, 0, '')",
            (index + 1, "guid%d" % index, US.join(fields), fields[1]),
        )
    db.commit()
    db.close()


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: make-sample-apkg.py <out.apkg>")
    out = sys.argv[1]
    handle, collection = tempfile.mkstemp(suffix=".anki21")
    os.close(handle)
    os.remove(collection)
    try:
        write_collection(collection)
        with zipfile.ZipFile(out, "w") as archive:
            archive.write(collection, "collection.anki21")
            # No media in this deck, but the manifest entry is always present in a real export.
            archive.writestr("media", json.dumps({}))
    finally:
        if os.path.exists(collection):
            os.remove(collection)
    print(out)


if __name__ == "__main__":
    main()
