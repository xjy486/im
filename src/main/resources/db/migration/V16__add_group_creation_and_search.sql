CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE groups
    ADD COLUMN name_normalized VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN description_normalized VARCHAR(1000) NOT NULL DEFAULT '';

UPDATE groups
SET name_normalized = LOWER(BTRIM(REGEXP_REPLACE(name, E'\\s+', ' ', 'g'))),
    description_normalized = LOWER(BTRIM(REGEXP_REPLACE(description, E'\\s+', ' ', 'g')));

CREATE OR REPLACE FUNCTION normalize_group_search_fields()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.name_normalized := LOWER(BTRIM(REGEXP_REPLACE(NEW.name, E'\\s+', ' ', 'g')));
    NEW.description_normalized := LOWER(BTRIM(REGEXP_REPLACE(NEW.description, E'\\s+', ' ', 'g')));
    RETURN NEW;
END;
$$;

CREATE TRIGGER groups_search_fields_trigger
    BEFORE INSERT OR UPDATE OF name, description ON groups
    FOR EACH ROW
    EXECUTE FUNCTION normalize_group_search_fields();

CREATE INDEX groups_public_search_idx
    ON groups
    USING GIN ((name_normalized || ' ' || description_normalized) gin_trgm_ops)
    WHERE status = 'ACTIVE' AND visibility = 'PUBLIC';

CREATE OR REPLACE FUNCTION enforce_group_member_limit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    active_member_count INTEGER;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM groups
        WHERE conversation_id = NEW.conversation_id
          AND status = 'ACTIVE'
    ) THEN
        RETURN NEW;
    END IF;

    PERFORM 1
    FROM groups
    WHERE conversation_id = NEW.conversation_id
      AND status = 'ACTIVE'
    FOR UPDATE;

    SELECT COUNT(*)
    INTO active_member_count
    FROM conversation_members
    WHERE conversation_id = NEW.conversation_id
      AND status = 'ACTIVE';

    IF active_member_count >= 100 THEN
        RAISE EXCEPTION 'group member limit exceeded'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER conversation_members_limit_trigger
    BEFORE INSERT OR UPDATE OF status ON conversation_members
    FOR EACH ROW
    WHEN (NEW.status = 'ACTIVE')
    EXECUTE FUNCTION enforce_group_member_limit();
