"""Writes a 10 MB file that starts as a PDF, for the demo's browser uploads: make_pdf.py <path>."""
import os
import sys

SIZE = 10 * 1024 * 1024
HEAD = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
        b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
        b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n%")
TAIL = b"\ntrailer<</Root 1 0 R>>\n%%EOF\n"

if len(sys.argv) != 2:
    sys.exit("usage: make_pdf.py <path>")
with open(sys.argv[1], "wb") as f:
    f.write(HEAD)
    f.write(os.urandom(SIZE - len(HEAD) - len(TAIL)))
    f.write(TAIL)
