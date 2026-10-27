"""Generate synthetic rows for the isolated polling benchmark database."""

def generate():
    rows = range(1, 1001)
    statements = []
    def insert(table, columns, values):
        statements.append(f"INSERT INTO {table}({columns}) VALUES " + ",".join(values) + ";")
    insert("users", "user_id,email,nickname",
           [f"({10000+i},'poll{i}@example.test','poll{i}')" for i in rows])
    for base in (10000, 20000, 30000):
        insert("trips", "trip_id,user_id,trip_name,start_date,end_date,processing_status",
               [f"({base+i},{10000+i},'poll{i}','2026-01-01','2026-01-02','COMPLETED')" for i in rows])
    insert("stories", "story_id,trip_id,mood,story_summary,processing_status",
           [f"({i},{10000+i},'EMOTIONAL','fixture','COMPLETED')" for i in rows])
    insert("story_generation_jobs", "generation_id,execution_id,trip_id,user_id,mood,status,progress,story_id",
           [f"({i},'poll-story-{i}',{10000+i},{10000+i},'EMOTIONAL','COMPLETED',100,{i})" for i in rows])
    insert("trip_creation_executions", "execution_id,trip_id,user_id,status,total_attachment_count,input_closed,process_published_at,process_delivery_status",
           [f"('poll-creation-{i}',{20000+i},{10000+i},'COMPLETED',10,TRUE,UTC_TIMESTAMP(6),'SENT')" for i in rows])
    insert("additional_attachment_executions", "execution_id,trip_id,user_id,status,total_attachment_count,analysis_attachment_count,input_closed,process_published_at,ended_at",
           [f"('poll-update-{i}',{30000+i},{10000+i},'COMPLETED',10,10,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))" for i in rows])
    return "\n".join(statements) + "\n"

if __name__ == "__main__":
    print(generate(), end="")
