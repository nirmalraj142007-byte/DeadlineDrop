# Prior art: attach, submit, and the cut-off

Read on 18 Nov 2026. Where a page could not be read, this says so and describes nothing.

**Moodle** ([assignment settings](https://docs.moodle.org/en/Assignment_settings)).
The page could not be read: docs.moodle.org answered with a 403 and a bot-check
challenge, which I did not work around. I therefore make no claim about Moodle's due
date versus cut-off date.

**Canvas.** The page on
[creating an online assignment](https://community.instructure.com/en/kb/articles/660671-how-do-i-create-an-online-assignment)
says a student can submit text, a link, media or uploaded files, and with several
types enabled uses only one per submission. It says nothing about the order of
attaching and submitting, or about a slow upload at a deadline. The page on
[due dates versus availability dates](https://community.instructure.com/en/kb/articles/660663-what-is-the-difference-between-assignment-due-dates-and-availability-dates)
loaded without readable text, so I do not describe Canvas's "until" date or late flag.

**Google Classroom** ([turn in an assignment](https://support.google.com/edu/classroom/answer/6020285),
[grade and return](https://support.google.com/edu/classroom/answer/6020294)).
The student adds a file under their work first and then presses Turn in. Anything turned
in after the due date is recorded as late, and the teacher page says past-due work can get
a draft score of zero by default. Neither page says what happens to an upload still
running at the due date.

**Where DeadlineDrop differs.** In Classroom the file is attached first and the turn-in
comes after, and the pages I could read do not say what happens when the bytes are still
moving at the due date. DeadlineDrop takes the time first: the browser sends only the
file's SHA-256, the server stamps that request on arrival, and the bytes follow. The
grace window then accepts only bytes whose hash matches a commitment that was already
stamped.
