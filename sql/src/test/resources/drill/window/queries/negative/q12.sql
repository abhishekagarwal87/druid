select LAST_VALUE(a1) OVER (PARTITION BY b1 ORDER BY c1 ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING) as t from t1;
