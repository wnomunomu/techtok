ALTER TABLE tech_articles ALTER COLUMN summary DROP NOT NULL;
ALTER TABLE tech_articles ADD COLUMN summary_source TEXT;
