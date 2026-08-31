-- Script de inicialización de Postgres para el entorno de desarrollo local.
-- Este archivo es ejecutado automáticamente por la imagen oficial de postgres
-- en /docker-entrypoint-initdb.d/ SOLO la primera vez que el volumen está vacío.
-- Si el volumen ya existe y tiene datos, este script NO se vuelve a ejecutar.
-- Para re-ejecutarlo, hacé: docker-compose down -v && docker-compose up -d

-- El usuario 'ratchet' y la base 'ratchet_db' son creados automáticamente
-- por las variables POSTGRES_USER / POSTGRES_DB del contenedor. Aquí solo
-- creamos las bases adicionales y les damos permisos al mismo usuario.

CREATE DATABASE payment_db;
GRANT ALL PRIVILEGES ON DATABASE payment_db TO ratchet;

CREATE DATABASE notification_db;
GRANT ALL PRIVILEGES ON DATABASE notification_db TO ratchet;
