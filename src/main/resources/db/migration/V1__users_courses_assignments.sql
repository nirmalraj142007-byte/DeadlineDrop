CREATE TYPE user_role AS ENUM ('STUDENT', 'TEACHER', 'ADMIN');

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    password_hash text NOT NULL,
    role user_role NOT NULL,
    display_name text NOT NULL CHECK (char_length(display_name) <= 100),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE courses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    title text NOT NULL,
    teacher_id uuid NOT NULL REFERENCES users(id),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_courses_teacher ON courses(teacher_id);

CREATE TABLE enrollments (
    course_id uuid NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    student_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (course_id, student_id)
);

CREATE INDEX ix_enroll_student ON enrollments(student_id);

CREATE TABLE assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id uuid NOT NULL REFERENCES courses(id),
    title text NOT NULL,
    deadline_at timestamptz NOT NULL,
    grace_seconds int NOT NULL DEFAULT 900 CHECK (grace_seconds BETWEEN 0 AND 3600),
    max_bytes bigint NOT NULL CHECK (max_bytes BETWEEN 1 AND 26214400),
    allowed_types text[] NOT NULL,
    created_by uuid NOT NULL REFERENCES users(id),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_assign_course ON assignments(course_id);
