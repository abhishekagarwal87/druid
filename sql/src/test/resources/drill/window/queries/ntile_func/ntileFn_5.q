SELECT col7 , NTILE(2) OVER (PARTITION by col7 ORDER by col4) tile FROM "allTypsUniq.parquet"
