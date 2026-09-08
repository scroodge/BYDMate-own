package com.bydmate.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** DDL shared by the v17→v18 migration and fresh-v18 database callback. */
object QueueStorageSchema {
    fun installAccounting(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queue_storage_metadata (
                id INTEGER NOT NULL PRIMARY KEY,
                totalPayloadBytes INTEGER NOT NULL,
                unknownPayloadRows INTEGER NOT NULL,
                databaseBytes INTEGER NOT NULL,
                walBytes INTEGER NOT NULL,
                shmBytes INTEGER NOT NULL,
                allocatableBytes INTEGER NOT NULL,
                measuredAt INTEGER NOT NULL,
                backfillComplete INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO queue_storage_metadata(
                id,totalPayloadBytes,unknownPayloadRows,databaseBytes,walBytes,shmBytes,
                allocatableBytes,measuredAt,backfillComplete
            ) SELECT 1,
                COALESCE(SUM(payloadBytes),0),
                COALESCE(SUM(CASE WHEN payloadBytes=0 THEN 1 ELSE 0 END),0),
                0,0,0,0,0,
                CASE WHEN COALESCE(SUM(CASE WHEN payloadBytes=0 THEN 1 ELSE 0 END),0)=0
                    THEN 1 ELSE 0 END
            FROM cloud_sync_queue
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS queue_storage_after_insert
            AFTER INSERT ON cloud_sync_queue BEGIN
              UPDATE queue_storage_metadata
              SET totalPayloadBytes=totalPayloadBytes+NEW.payloadBytes,
                  unknownPayloadRows=unknownPayloadRows+CASE WHEN NEW.payloadBytes=0 THEN 1 ELSE 0 END,
                  backfillComplete=CASE WHEN NEW.payloadBytes=0 THEN 0 ELSE backfillComplete END
              WHERE id=1;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS queue_storage_after_delete
            AFTER DELETE ON cloud_sync_queue BEGIN
              UPDATE queue_storage_metadata
              SET totalPayloadBytes=MAX(0,totalPayloadBytes-OLD.payloadBytes),
                  unknownPayloadRows=MAX(0,unknownPayloadRows-CASE WHEN OLD.payloadBytes=0 THEN 1 ELSE 0 END)
              WHERE id=1;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS queue_storage_after_payload_bytes_update
            AFTER UPDATE OF payloadBytes ON cloud_sync_queue
            WHEN OLD.payloadBytes != NEW.payloadBytes BEGIN
              UPDATE queue_storage_metadata
              SET totalPayloadBytes=MAX(0,totalPayloadBytes-OLD.payloadBytes+NEW.payloadBytes),
                  unknownPayloadRows=MAX(0,unknownPayloadRows
                    -CASE WHEN OLD.payloadBytes=0 THEN 1 ELSE 0 END
                    +CASE WHEN NEW.payloadBytes=0 THEN 1 ELSE 0 END)
              WHERE id=1;
            END
            """.trimIndent()
        )
    }
}
