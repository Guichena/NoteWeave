-- MySQL dump 10.13  Distrib 8.4.9, for Linux (x86_64)
--
-- Host: localhost    Database: noteweave
-- ------------------------------------------------------
-- Server version	8.4.9

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `artifact_job`
--

DROP TABLE IF EXISTS `artifact_job`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `artifact_job` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `action_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `style_profile_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `context_snapshot_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_scope_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `control_pack_json` longtext COLLATE utf8mb4_unicode_ci,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `result_title` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `latest_version_no` int NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `skill_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_requirement` longtext COLLATE utf8mb4_unicode_ci,
  `inputs_json` longtext COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_artifact_job_workspace_created` (`workspace_id`,`created_at`),
  KEY `idx_artifact_job_task` (`task_id`),
  CONSTRAINT `fk_artifact_job_task` FOREIGN KEY (`task_id`) REFERENCES `task` (`id`),
  CONSTRAINT `fk_artifact_job_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `artifact_job`
--

LOCK TABLES `artifact_job` WRITE;
/*!40000 ALTER TABLE `artifact_job` DISABLE KEYS */;
/*!40000 ALTER TABLE `artifact_job` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `artifact_version`
--

DROP TABLE IF EXISTS `artifact_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `artifact_version` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `artifact_job_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version_no` int NOT NULL,
  `title` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_markdown` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `result_payload_json` longtext COLLATE utf8mb4_unicode_ci,
  `trace_summary` longtext COLLATE utf8mb4_unicode_ci,
  `citations_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `skill_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_artifact_version_job_no` (`artifact_job_id`,`version_no`),
  KEY `idx_artifact_version_skill_created` (`skill_key`,`created_at`),
  CONSTRAINT `fk_artifact_version_job` FOREIGN KEY (`artifact_job_id`) REFERENCES `artifact_job` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `artifact_version`
--

LOCK TABLES `artifact_version` WRITE;
/*!40000 ALTER TABLE `artifact_version` DISABLE KEYS */;
/*!40000 ALTER TABLE `artifact_version` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `citation`
--

DROP TABLE IF EXISTS `citation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `citation` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_snapshot_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_chunk_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL,
  `quote_text` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `page_no` int DEFAULT NULL,
  `location_info` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_citation_workspace` (`workspace_id`),
  KEY `fk_citation_source` (`source_id`),
  KEY `fk_citation_snapshot` (`source_snapshot_id`),
  KEY `fk_citation_chunk` (`source_chunk_id`),
  CONSTRAINT `fk_citation_chunk` FOREIGN KEY (`source_chunk_id`) REFERENCES `source_chunk` (`id`),
  CONSTRAINT `fk_citation_snapshot` FOREIGN KEY (`source_snapshot_id`) REFERENCES `source_snapshot` (`id`),
  CONSTRAINT `fk_citation_source` FOREIGN KEY (`source_id`) REFERENCES `source` (`id`),
  CONSTRAINT `fk_citation_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `citation`
--

LOCK TABLES `citation` WRITE;
/*!40000 ALTER TABLE `citation` DISABLE KEYS */;
/*!40000 ALTER TABLE `citation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `conversation`
--

DROP TABLE IF EXISTS `conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `conversation_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_active_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_workspace_active` (`workspace_id`,`last_active_at`),
  CONSTRAINT `fk_conversation_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `conversation`
--

LOCK TABLES `conversation` WRITE;
/*!40000 ALTER TABLE `conversation` DISABLE KEYS */;
/*!40000 ALTER TABLE `conversation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `conversation_message`
--

DROP TABLE IF EXISTS `conversation_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation_message` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `conversation_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message_seq` int NOT NULL,
  `role` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `answer_mode` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `assistant_request_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_message_seq` (`conversation_id`,`message_seq`),
  KEY `fk_conversation_message_workspace` (`workspace_id`),
  KEY `idx_conversation_message_created` (`conversation_id`,`created_at`),
  CONSTRAINT `fk_conversation_message_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`),
  CONSTRAINT `fk_conversation_message_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `conversation_message`
--

LOCK TABLES `conversation_message` WRITE;
/*!40000 ALTER TABLE `conversation_message` DISABLE KEYS */;
/*!40000 ALTER TABLE `conversation_message` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `conversation_scope`
--

DROP TABLE IF EXISTS `conversation_scope`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation_scope` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `conversation_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope_ref_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_conversation_scope_conversation` (`conversation_id`),
  CONSTRAINT `fk_conversation_scope_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `conversation_scope`
--

LOCK TABLES `conversation_scope` WRITE;
/*!40000 ALTER TABLE `conversation_scope` DISABLE KEYS */;
/*!40000 ALTER TABLE `conversation_scope` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `document_upload`
--

DROP TABLE IF EXISTS `document_upload`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `document_upload` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `file_name` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL,
  `file_size` bigint NOT NULL,
  `mime_type` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `chunk_size` int NOT NULL,
  `total_chunks` int NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `uploaded_chunks` int NOT NULL DEFAULT '0',
  `source_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `task_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_document_upload_workspace` (`workspace_id`),
  CONSTRAINT `fk_document_upload_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `document_upload`
--

LOCK TABLES `document_upload` WRITE;
/*!40000 ALTER TABLE `document_upload` DISABLE KEYS */;
/*!40000 ALTER TABLE `document_upload` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_object`
--

DROP TABLE IF EXISTS `file_object`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_object` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `object_key` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sha256` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `file_size` bigint NOT NULL,
  `mime_type` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ref_count` int NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_object_workspace_hash` (`workspace_id`,`sha256`),
  CONSTRAINT `fk_file_object_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_object`
--

LOCK TABLES `file_object` WRITE;
/*!40000 ALTER TABLE `file_object` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_object` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `script` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `flyway_schema_history`
--

LOCK TABLES `flyway_schema_history` WRITE;
/*!40000 ALTER TABLE `flyway_schema_history` DISABLE KEYS */;
INSERT INTO `flyway_schema_history` VALUES (1,'001','create user and workspace tables','SQL','V001__create_user_and_workspace_tables.sql',298563861,'noteweave','2026-07-09 13:41:27',421,1),(2,'002','create task and outbox tables','SQL','V002__create_task_and_outbox_tables.sql',815596747,'noteweave','2026-07-09 13:41:27',287,1),(3,'003','create source core tables','SQL','V003__create_source_core_tables.sql',-924114457,'noteweave','2026-07-09 13:41:28',320,1),(4,'004','create source read tables','SQL','V004__create_source_read_tables.sql',-1526006031,'noteweave','2026-07-09 13:41:28',131,1),(5,'005','create conversation tables','SQL','V005__create_conversation_tables.sql',91821131,'noteweave','2026-07-09 13:41:28',236,1),(6,'006','create citation tables','SQL','V006__create_citation_tables.sql',1583165947,'noteweave','2026-07-09 13:41:28',136,1),(7,'007','create knowledge tables','SQL','V007__create_knowledge_tables.sql',398805424,'noteweave','2026-07-09 13:41:29',282,1),(8,'008','add source metadata and knowledge versioning support','SQL','V008__add_source_metadata_and_knowledge_versioning_support.sql',2006241866,'noteweave','2026-07-09 13:41:29',260,1),(9,'009','add workspace indexing strategy','SQL','V009__add_workspace_indexing_strategy.sql',-1388266339,'noteweave','2026-07-09 13:41:29',129,1),(10,'010','add wiki log entries','SQL','V010__add_wiki_log_entries.sql',-668820674,'noteweave','2026-07-09 13:41:29',72,1),(11,'011','create memory tables','SQL','V011__create_memory_tables.sql',644362922,'noteweave','2026-07-09 13:41:30',330,1),(12,'012','create research and artifact tables','SQL','V012__create_research_and_artifact_tables.sql',961294875,'noteweave','2026-07-09 13:41:30',309,1),(13,'013','add research report source mapping','SQL','V013__add_research_report_source_mapping.sql',-1983268385,'noteweave','2026-07-09 13:41:30',272,1),(14,'014','add research closed loop state tables','SQL','V014__add_research_closed_loop_state_tables.sql',-1636422757,'noteweave','2026-07-09 13:41:31',396,1),(15,'015','add research checkpoint and evidence tables','SQL','V015__add_research_checkpoint_and_evidence_tables.sql',1205127816,'noteweave','2026-07-09 13:41:31',285,1),(16,'016','add research resume checkpoint columns','SQL','V016__add_research_resume_checkpoint_columns.sql',-38398729,'noteweave','2026-07-09 13:41:31',250,1),(17,'017','add research intent json','SQL','V017__add_research_intent_json.sql',-708001083,'noteweave','2026-07-09 13:41:31',105,1),(18,'018','add skill first fields to artifact job','SQL','V018__add_skill_first_fields_to_artifact_job.sql',-727334320,'noteweave','2026-07-09 13:41:32',212,1),(19,'019','add skill key to artifact version','SQL','V019__add_skill_key_to_artifact_version.sql',710389177,'noteweave','2026-07-09 13:41:32',96,1),(20,'020','add research cell verdict','SQL','V020__add_research_cell_verdict.sql',2130477437,'noteweave','2026-07-09 13:41:32',126,1),(21,'021','make artifact job action key nullable','SQL','V021__make_artifact_job_action_key_nullable.sql',-2008868013,'noteweave','2026-07-09 13:41:32',96,1);
/*!40000 ALTER TABLE `flyway_schema_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `knowledge_item`
--

DROP TABLE IF EXISTS `knowledge_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_item` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `item_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `page_kind` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `title` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `latest_version_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_item_workspace_type_updated` (`workspace_id`,`item_type`,`updated_at`),
  CONSTRAINT `fk_knowledge_item_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `knowledge_item`
--

LOCK TABLES `knowledge_item` WRITE;
/*!40000 ALTER TABLE `knowledge_item` DISABLE KEYS */;
/*!40000 ALTER TABLE `knowledge_item` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `knowledge_item_link`
--

DROP TABLE IF EXISTS `knowledge_item_link`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_item_link` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_item_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_item_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_title` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL,
  `relation_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `relation_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `mention_count` int NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_knowledge_item_link_workspace` (`workspace_id`),
  KEY `idx_knowledge_item_link_source` (`source_item_id`,`relation_status`),
  KEY `idx_knowledge_item_link_target` (`target_item_id`,`relation_status`),
  CONSTRAINT `fk_knowledge_item_link_source` FOREIGN KEY (`source_item_id`) REFERENCES `knowledge_item` (`id`),
  CONSTRAINT `fk_knowledge_item_link_target` FOREIGN KEY (`target_item_id`) REFERENCES `knowledge_item` (`id`),
  CONSTRAINT `fk_knowledge_item_link_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `knowledge_item_link`
--

LOCK TABLES `knowledge_item_link` WRITE;
/*!40000 ALTER TABLE `knowledge_item_link` DISABLE KEYS */;
/*!40000 ALTER TABLE `knowledge_item_link` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `knowledge_version`
--

DROP TABLE IF EXISTS `knowledge_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_version` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `item_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version_no` int NOT NULL,
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `summary` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_message_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_version_no` (`item_id`,`version_no`),
  KEY `fk_knowledge_version_message` (`source_message_id`),
  CONSTRAINT `fk_knowledge_version_item` FOREIGN KEY (`item_id`) REFERENCES `knowledge_item` (`id`),
  CONSTRAINT `fk_knowledge_version_message` FOREIGN KEY (`source_message_id`) REFERENCES `conversation_message` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `knowledge_version`
--

LOCK TABLES `knowledge_version` WRITE;
/*!40000 ALTER TABLE `knowledge_version` DISABLE KEYS */;
/*!40000 ALTER TABLE `knowledge_version` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `knowledge_version_citation`
--

DROP TABLE IF EXISTS `knowledge_version_citation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_version_citation` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `knowledge_version_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `citation_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_knowledge_version_citation_version` (`knowledge_version_id`),
  KEY `fk_knowledge_version_citation_citation` (`citation_id`),
  CONSTRAINT `fk_knowledge_version_citation_citation` FOREIGN KEY (`citation_id`) REFERENCES `citation` (`id`),
  CONSTRAINT `fk_knowledge_version_citation_version` FOREIGN KEY (`knowledge_version_id`) REFERENCES `knowledge_version` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `knowledge_version_citation`
--

LOCK TABLES `knowledge_version_citation` WRITE;
/*!40000 ALTER TABLE `knowledge_version_citation` DISABLE KEYS */;
/*!40000 ALTER TABLE `knowledge_version_citation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `memory_candidate`
--

DROP TABLE IF EXISTS `memory_candidate`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `memory_candidate` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `candidate_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `normalized_statement` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_neighborhood_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence_gate_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `novelty_score` decimal(5,4) NOT NULL,
  `marginal_utility_score` decimal(5,4) NOT NULL,
  `negative_memory_flag` tinyint(1) NOT NULL DEFAULT '0',
  `conflict_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `staleness_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `compile_policy_json` longtext COLLATE utf8mb4_unicode_ci,
  `forbidden_pattern_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_from_signal_ids_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `review_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_memory_candidate_user` (`user_id`),
  KEY `idx_memory_candidate_workspace_review` (`workspace_id`,`review_status`,`created_at`),
  CONSTRAINT `fk_memory_candidate_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_memory_candidate_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `memory_candidate`
--

LOCK TABLES `memory_candidate` WRITE;
/*!40000 ALTER TABLE `memory_candidate` DISABLE KEYS */;
/*!40000 ALTER TABLE `memory_candidate` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `memory_object`
--

DROP TABLE IF EXISTS `memory_object`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `memory_object` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `memory_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `memory_scope` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `canonical_statement` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_neighborhood_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `compile_policy_json` longtext COLLATE utf8mb4_unicode_ci,
  `forbidden_pattern_json` longtext COLLATE utf8mb4_unicode_ci,
  `ledger_json` longtext COLLATE utf8mb4_unicode_ci,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_memory_object_user` (`user_id`),
  KEY `idx_memory_object_workspace_status` (`workspace_id`,`status`,`created_at`),
  CONSTRAINT `fk_memory_object_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_memory_object_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `memory_object`
--

LOCK TABLES `memory_object` WRITE;
/*!40000 ALTER TABLE `memory_object` DISABLE KEYS */;
/*!40000 ALTER TABLE `memory_object` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `memory_signal`
--

DROP TABLE IF EXISTS `memory_signal`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `memory_signal` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `signal_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `signal_text` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_neighborhood` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `compile_hints_json` longtext COLLATE utf8mb4_unicode_ci,
  `confidence_score` decimal(5,4) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_memory_signal_user` (`user_id`),
  KEY `idx_memory_signal_workspace_created` (`workspace_id`,`created_at`),
  KEY `idx_memory_signal_workspace_neighborhood` (`workspace_id`,`task_neighborhood`,`created_at`),
  CONSTRAINT `fk_memory_signal_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_memory_signal_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `memory_signal`
--

LOCK TABLES `memory_signal` WRITE;
/*!40000 ALTER TABLE `memory_signal` DISABLE KEYS */;
/*!40000 ALTER TABLE `memory_signal` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `memory_usage_log`
--

DROP TABLE IF EXISTS `memory_usage_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `memory_usage_log` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `memory_object_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `compiled_as` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `used_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `effect_feedback` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_memory_usage_object` (`memory_object_id`),
  KEY `idx_memory_usage_target` (`workspace_id`,`task_type`,`target_type`,`used_at`),
  CONSTRAINT `fk_memory_usage_object` FOREIGN KEY (`memory_object_id`) REFERENCES `memory_object` (`id`),
  CONSTRAINT `fk_memory_usage_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `memory_usage_log`
--

LOCK TABLES `memory_usage_log` WRITE;
/*!40000 ALTER TABLE `memory_usage_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `memory_usage_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `message_citation`
--

DROP TABLE IF EXISTS `message_citation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `message_citation` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `citation_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_message_citation_message` (`message_id`),
  KEY `fk_message_citation_citation` (`citation_id`),
  CONSTRAINT `fk_message_citation_citation` FOREIGN KEY (`citation_id`) REFERENCES `citation` (`id`),
  CONSTRAINT `fk_message_citation_message` FOREIGN KEY (`message_id`) REFERENCES `conversation_message` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `message_citation`
--

LOCK TABLES `message_citation` WRITE;
/*!40000 ALTER TABLE `message_citation` DISABLE KEYS */;
/*!40000 ALTER TABLE `message_citation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_branch`
--

DROP TABLE IF EXISTS `research_branch`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_branch` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `branch_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `parent_branch_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `branch_reason` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `branch_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `hypothesis_summary` longtext COLLATE utf8mb4_unicode_ci,
  `target_evidence_ids_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_round` int NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_research_branch_run_key` (`research_run_id`,`branch_key`),
  KEY `idx_research_branch_run_created` (`research_run_id`,`created_at`),
  CONSTRAINT `fk_research_branch_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_branch`
--

LOCK TABLES `research_branch` WRITE;
/*!40000 ALTER TABLE `research_branch` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_branch` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_cell`
--

DROP TABLE IF EXISTS `research_cell`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_cell` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_row_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cell_key` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `branch_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `column_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `candidate_value` longtext COLLATE utf8mb4_unicode_ci,
  `cell_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `confidence_score` decimal(5,4) DEFAULT NULL,
  `evidence_refs_json` longtext COLLATE utf8mb4_unicode_ci,
  `last_verifier_decision` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `repair_count` int NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `verdict` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `verdict_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `verdict_confidence` decimal(5,4) DEFAULT NULL,
  `verdict_round` int DEFAULT NULL,
  `verdict_used_llm` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_research_cell_run_key` (`research_run_id`,`cell_key`),
  KEY `idx_research_cell_row` (`research_row_id`,`column_key`),
  KEY `idx_research_cell_run_updated` (`research_run_id`,`updated_at`),
  KEY `idx_research_cell_verdict` (`verdict`,`verdict_round`),
  CONSTRAINT `fk_research_cell_row` FOREIGN KEY (`research_row_id`) REFERENCES `research_row` (`id`),
  CONSTRAINT `fk_research_cell_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_cell`
--

LOCK TABLES `research_cell` WRITE;
/*!40000 ALTER TABLE `research_cell` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_cell` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_cell_evidence`
--

DROP TABLE IF EXISTS `research_cell_evidence`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_cell_evidence` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_cell_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_evidence_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_research_cell_evidence_cell_source` (`research_cell_id`,`source_evidence_id`),
  KEY `fk_research_cell_evidence_source` (`source_evidence_id`),
  KEY `idx_research_cell_evidence_run_created` (`research_run_id`,`created_at`),
  CONSTRAINT `fk_research_cell_evidence_cell` FOREIGN KEY (`research_cell_id`) REFERENCES `research_cell` (`id`),
  CONSTRAINT `fk_research_cell_evidence_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`),
  CONSTRAINT `fk_research_cell_evidence_source` FOREIGN KEY (`source_evidence_id`) REFERENCES `source_evidence` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_cell_evidence`
--

LOCK TABLES `research_cell_evidence` WRITE;
/*!40000 ALTER TABLE `research_cell_evidence` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_cell_evidence` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_execution_checkpoint`
--

DROP TABLE IF EXISTS `research_execution_checkpoint`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_execution_checkpoint` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `checkpoint_no` int NOT NULL,
  `snapshot_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `object_key` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload_sha256` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_size` bigint NOT NULL,
  `active_branch_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `final_loop_decision` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `summary_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_research_checkpoint_run_no` (`research_run_id`,`checkpoint_no`),
  KEY `idx_research_checkpoint_run_created` (`research_run_id`,`created_at`),
  CONSTRAINT `fk_research_checkpoint_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_execution_checkpoint`
--

LOCK TABLES `research_execution_checkpoint` WRITE;
/*!40000 ALTER TABLE `research_execution_checkpoint` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_execution_checkpoint` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_row`
--

DROP TABLE IF EXISTS `research_row`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_row` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `row_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `branch_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_title` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `search_query` longtext COLLATE utf8mb4_unicode_ci,
  `read_focus` longtext COLLATE utf8mb4_unicode_ci,
  `evidence_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `row_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `relation_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `support_score` decimal(5,4) DEFAULT NULL,
  `conflict_score` decimal(5,4) DEFAULT NULL,
  `support_level` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `verification_status` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `verifier_note` longtext COLLATE utf8mb4_unicode_ci,
  `repair_hint` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_research_row_run_key` (`research_run_id`,`row_key`),
  KEY `idx_research_row_run_updated` (`research_run_id`,`updated_at`),
  CONSTRAINT `fk_research_row_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_row`
--

LOCK TABLES `research_row` WRITE;
/*!40000 ALTER TABLE `research_row` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_row` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_run`
--

DROP TABLE IF EXISTS `research_run`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_run` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `question` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `profile_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `context_snapshot_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_scope_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `control_pack_json` longtext COLLATE utf8mb4_unicode_ci,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `final_report_title` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `final_report_markdown` longtext COLLATE utf8mb4_unicode_ci,
  `trace_summary` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `report_source_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `resumed_from_research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `resumed_from_checkpoint_no` int DEFAULT NULL,
  `research_intent_json` longtext COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_research_run_workspace_created` (`workspace_id`,`created_at`),
  KEY `idx_research_run_task` (`task_id`),
  KEY `idx_research_run_resumed_from` (`resumed_from_research_run_id`,`resumed_from_checkpoint_no`),
  CONSTRAINT `fk_research_run_task` FOREIGN KEY (`task_id`) REFERENCES `task` (`id`),
  CONSTRAINT `fk_research_run_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_run`
--

LOCK TABLES `research_run` WRITE;
/*!40000 ALTER TABLE `research_run` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_run` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_trace`
--

DROP TABLE IF EXISTS `research_trace`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_trace` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `trace_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `trace_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payload_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_research_trace_run_created` (`research_run_id`,`created_at`),
  CONSTRAINT `fk_research_trace_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_trace`
--

LOCK TABLES `research_trace` WRITE;
/*!40000 ALTER TABLE `research_trace` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_trace` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `research_verifier_decision`
--

DROP TABLE IF EXISTS `research_verifier_decision`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_verifier_decision` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `branch_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `decision_scope` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `decision_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason_code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `evidence_ids_json` longtext COLLATE utf8mb4_unicode_ci,
  `action_text` longtext COLLATE utf8mb4_unicode_ci,
  `decision_status` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `notes_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_research_verifier_run_created` (`research_run_id`,`created_at`),
  CONSTRAINT `fk_research_verifier_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `research_verifier_decision`
--

LOCK TABLES `research_verifier_decision` WRITE;
/*!40000 ALTER TABLE `research_verifier_decision` DISABLE KEYS */;
/*!40000 ALTER TABLE `research_verifier_decision` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `source`
--

DROP TABLE IF EXISTS `source`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `source` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `file_object_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `parse_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `index_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `summary` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tags_json` longtext COLLATE utf8mb4_unicode_ci,
  `metadata_json` longtext COLLATE utf8mb4_unicode_ci,
  `generated_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `generated_ref_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_source_file_object` (`file_object_id`),
  KEY `idx_source_workspace_status_updated` (`workspace_id`,`status`,`updated_at`),
  KEY `idx_source_workspace_parse_index` (`workspace_id`,`parse_status`,`index_status`),
  KEY `idx_source_workspace_type_updated` (`workspace_id`,`source_type`,`updated_at`),
  KEY `idx_source_generated_ref` (`generated_by`,`generated_ref_id`),
  CONSTRAINT `fk_source_file_object` FOREIGN KEY (`file_object_id`) REFERENCES `file_object` (`id`),
  CONSTRAINT `fk_source_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `source`
--

LOCK TABLES `source` WRITE;
/*!40000 ALTER TABLE `source` DISABLE KEYS */;
/*!40000 ALTER TABLE `source` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `source_chunk`
--

DROP TABLE IF EXISTS `source_chunk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `source_chunk` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_snapshot_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `chunk_no` int NOT NULL,
  `heading` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `token_estimate` int NOT NULL,
  `location_info` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_source_chunk_workspace` (`workspace_id`),
  KEY `fk_source_chunk_source` (`source_id`),
  KEY `idx_source_chunk_snapshot_no` (`source_snapshot_id`,`chunk_no`),
  CONSTRAINT `fk_source_chunk_snapshot` FOREIGN KEY (`source_snapshot_id`) REFERENCES `source_snapshot` (`id`),
  CONSTRAINT `fk_source_chunk_source` FOREIGN KEY (`source_id`) REFERENCES `source` (`id`),
  CONSTRAINT `fk_source_chunk_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `source_chunk`
--

LOCK TABLES `source_chunk` WRITE;
/*!40000 ALTER TABLE `source_chunk` DISABLE KEYS */;
/*!40000 ALTER TABLE `source_chunk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `source_evidence`
--

DROP TABLE IF EXISTS `source_evidence`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `source_evidence` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `research_run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `window_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_title` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_url` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `provider` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `adapter` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `search_query` longtext COLLATE utf8mb4_unicode_ci,
  `read_focus` longtext COLLATE utf8mb4_unicode_ci,
  `quote_text` longtext COLLATE utf8mb4_unicode_ci,
  `claim_text` longtext COLLATE utf8mb4_unicode_ci,
  `relation_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `support_score` decimal(5,4) DEFAULT NULL,
  `conflict_score` decimal(5,4) DEFAULT NULL,
  `snapshot_status` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `snapshot_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_source_evidence_run_key` (`research_run_id`,`evidence_key`),
  KEY `idx_source_evidence_run_created` (`research_run_id`,`created_at`),
  CONSTRAINT `fk_source_evidence_run` FOREIGN KEY (`research_run_id`) REFERENCES `research_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `source_evidence`
--

LOCK TABLES `source_evidence` WRITE;
/*!40000 ALTER TABLE `source_evidence` DISABLE KEYS */;
/*!40000 ALTER TABLE `source_evidence` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `source_snapshot`
--

DROP TABLE IF EXISTS `source_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `source_snapshot` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `file_object_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version_no` int NOT NULL,
  `object_key` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sha256` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `parse_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `index_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_snapshot_version` (`source_id`,`version_no`),
  KEY `fk_source_snapshot_file_object` (`file_object_id`),
  CONSTRAINT `fk_source_snapshot_file_object` FOREIGN KEY (`file_object_id`) REFERENCES `file_object` (`id`),
  CONSTRAINT `fk_source_snapshot_source` FOREIGN KEY (`source_id`) REFERENCES `source` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `source_snapshot`
--

LOCK TABLES `source_snapshot` WRITE;
/*!40000 ALTER TABLE `source_snapshot` DISABLE KEYS */;
/*!40000 ALTER TABLE `source_snapshot` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `source_window`
--

DROP TABLE IF EXISTS `source_window`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `source_window` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_chunk_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `window_no` int NOT NULL,
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `location_info` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_source_window_chunk` (`source_chunk_id`),
  CONSTRAINT `fk_source_window_chunk` FOREIGN KEY (`source_chunk_id`) REFERENCES `source_chunk` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `source_window`
--

LOCK TABLES `source_window` WRITE;
/*!40000 ALTER TABLE `source_window` DISABLE KEYS */;
/*!40000 ALTER TABLE `source_window` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `task`
--

DROP TABLE IF EXISTS `task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `progress_phase` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `progress_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `result_ref` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_task_workspace` (`workspace_id`),
  KEY `idx_task_status_created` (`task_status`,`created_at`),
  CONSTRAINT `fk_task_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `task`
--

LOCK TABLES `task` WRITE;
/*!40000 ALTER TABLE `task` DISABLE KEYS */;
/*!40000 ALTER TABLE `task` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `task_attempt`
--

DROP TABLE IF EXISTS `task_attempt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task_attempt` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempt_no` int NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `started_at` timestamp NULL DEFAULT NULL,
  `finished_at` timestamp NULL DEFAULT NULL,
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_task_attempt_task` (`task_id`),
  CONSTRAINT `fk_task_attempt_task` FOREIGN KEY (`task_id`) REFERENCES `task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `task_attempt`
--

LOCK TABLES `task_attempt` WRITE;
/*!40000 ALTER TABLE `task_attempt` DISABLE KEYS */;
/*!40000 ALTER TABLE `task_attempt` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `task_event`
--

DROP TABLE IF EXISTS `task_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task_event` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payload_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_task_event_task` (`task_id`),
  CONSTRAINT `fk_task_event_task` FOREIGN KEY (`task_id`) REFERENCES `task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `task_event`
--

LOCK TABLES `task_event` WRITE;
/*!40000 ALTER TABLE `task_event` DISABLE KEYS */;
/*!40000 ALTER TABLE `task_event` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `task_outbox`
--

DROP TABLE IF EXISTS `task_outbox`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task_outbox` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `topic` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message_key` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `sent_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_task_outbox_task` (`task_id`),
  KEY `idx_task_outbox_status_created` (`status`,`created_at`),
  CONSTRAINT `fk_task_outbox_task` FOREIGN KEY (`task_id`) REFERENCES `task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `task_outbox`
--

LOCK TABLES `task_outbox` WRITE;
/*!40000 ALTER TABLE `task_outbox` DISABLE KEYS */;
/*!40000 ALTER TABLE `task_outbox` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `topic_scope`
--

DROP TABLE IF EXISTS `topic_scope`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `topic_scope` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_topic_scope_workspace` (`workspace_id`),
  CONSTRAINT `fk_topic_scope_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `topic_scope`
--

LOCK TABLES `topic_scope` WRITE;
/*!40000 ALTER TABLE `topic_scope` DISABLE KEYS */;
/*!40000 ALTER TABLE `topic_scope` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ui_context_snapshot`
--

DROP TABLE IF EXISTS `ui_context_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ui_context_snapshot` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `conversation_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_ui_context_snapshot_workspace` (`workspace_id`),
  CONSTRAINT `fk_ui_context_snapshot_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ui_context_snapshot`
--

LOCK TABLES `ui_context_snapshot` WRITE;
/*!40000 ALTER TABLE `ui_context_snapshot` DISABLE KEYS */;
/*!40000 ALTER TABLE `ui_context_snapshot` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `upload_chunk`
--

DROP TABLE IF EXISTS `upload_chunk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `upload_chunk` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `upload_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `chunk_index` int NOT NULL,
  `content_md5` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `object_key` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `byte_size` bigint NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_upload_chunk_index` (`upload_id`,`chunk_index`),
  CONSTRAINT `fk_upload_chunk_upload` FOREIGN KEY (`upload_id`) REFERENCES `document_upload` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `upload_chunk`
--

LOCK TABLES `upload_chunk` WRITE;
/*!40000 ALTER TABLE `upload_chunk` DISABLE KEYS */;
/*!40000 ALTER TABLE `upload_chunk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_session`
--

DROP TABLE IF EXISTS `user_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_session` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_token` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_user_session_user` (`user_id`),
  CONSTRAINT `fk_user_session_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_session`
--

LOCK TABLES `user_session` WRITE;
/*!40000 ALTER TABLE `user_session` DISABLE KEYS */;
/*!40000 ALTER TABLE `user_session` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `username` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_username` (`username`),
  UNIQUE KEY `uk_users_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
INSERT INTO `users` VALUES ('local-user','local-user','local-user@noteweave.local','Local User','ACTIVE','2026-07-09 13:41:27','2026-07-09 13:41:27');
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `wiki_log_entry`
--

DROP TABLE IF EXISTS `wiki_log_entry`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wiki_log_entry` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `item_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `event_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_wiki_log_item` (`item_id`),
  KEY `idx_wiki_log_workspace_created` (`workspace_id`,`created_at`),
  CONSTRAINT `fk_wiki_log_item` FOREIGN KEY (`item_id`) REFERENCES `knowledge_item` (`id`),
  CONSTRAINT `fk_wiki_log_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `wiki_log_entry`
--

LOCK TABLES `wiki_log_entry` WRITE;
/*!40000 ALTER TABLE `wiki_log_entry` DISABLE KEYS */;
/*!40000 ALTER TABLE `wiki_log_entry` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `workspace`
--

DROP TABLE IF EXISTS `workspace`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workspace` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `wiki_enabled` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_workspace_owner_updated` (`owner_id`,`updated_at`),
  KEY `idx_workspace_wiki_enabled` (`wiki_enabled`,`updated_at`),
  CONSTRAINT `fk_workspace_owner` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `workspace`
--

LOCK TABLES `workspace` WRITE;
/*!40000 ALTER TABLE `workspace` DISABLE KEYS */;
/*!40000 ALTER TABLE `workspace` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `workspace_member`
--

DROP TABLE IF EXISTS `workspace_member`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workspace_member` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_member_user` (`workspace_id`,`user_id`),
  KEY `fk_workspace_member_user` (`user_id`),
  CONSTRAINT `fk_workspace_member_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_workspace_member_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `workspace_member`
--

LOCK TABLES `workspace_member` WRITE;
/*!40000 ALTER TABLE `workspace_member` DISABLE KEYS */;
/*!40000 ALTER TABLE `workspace_member` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping routines for database 'noteweave'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-07-12 13:46:50
