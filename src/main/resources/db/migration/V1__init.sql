

CREATE TABLE schema_init (
        id INT PRIMARY KEY,
        initialized_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO schema_init (id) VALUES (1);