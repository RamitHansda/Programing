"""Seed local SQLite + DuckDB demo databases with multiple tables."""

from __future__ import annotations

import argparse
import random
import sqlite3
from datetime import date, timedelta
from pathlib import Path

import duckdb


CUSTOMERS = 500
ORDERS = 2000
EVENTS = 5000


def seed_sqlite(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.unlink()
    conn = sqlite3.connect(path)
    cur = conn.cursor()
    cur.executescript(
        """
        CREATE TABLE customers (
          customer_id INTEGER PRIMARY KEY,
          email TEXT NOT NULL,
          country TEXT,
          signup_date TEXT,
          lifetime_value REAL,
          is_active INTEGER
        );
        CREATE TABLE orders (
          order_id INTEGER PRIMARY KEY,
          customer_id INTEGER,
          order_ts TEXT,
          amount REAL,
          status TEXT,
          FOREIGN KEY(customer_id) REFERENCES customers(customer_id)
        );
        CREATE TABLE events (
          event_id INTEGER PRIMARY KEY,
          customer_id INTEGER,
          event_name TEXT,
          event_ts TEXT,
          properties TEXT
        );
        """
    )
    rng = random.Random(42)
    countries = ["US", "CA", "GB", "DE", "IN", "BR", None]
    customers = []
    for i in range(1, CUSTOMERS + 1):
        customers.append(
            (
                i,
                f"user{i}@example.com",
                rng.choice(countries),
                (date(2022, 1, 1) + timedelta(days=rng.randint(0, 800))).isoformat(),
                round(rng.uniform(0, 5000), 2) if rng.random() > 0.05 else None,
                1 if rng.random() > 0.2 else 0,
            )
        )
    cur.executemany(
        "INSERT INTO customers VALUES (?,?,?,?,?,?)",
        customers,
    )

    statuses = ["pending", "paid", "shipped", "cancelled", "refunded"]
    orders = []
    for i in range(1, ORDERS + 1):
        orders.append(
            (
                i,
                rng.randint(1, CUSTOMERS),
                (date(2023, 1, 1) + timedelta(days=rng.randint(0, 400))).isoformat()
                + f"T{rng.randint(0,23):02d}:00:00",
                round(rng.uniform(5, 800), 2),
                rng.choice(statuses),
            )
        )
    cur.executemany("INSERT INTO orders VALUES (?,?,?,?,?)", orders)

    names = ["view", "click", "add_to_cart", "purchase", "login"]
    events = []
    for i in range(1, EVENTS + 1):
        events.append(
            (
                i,
                rng.randint(1, CUSTOMERS) if rng.random() > 0.1 else None,
                rng.choice(names),
                (date(2024, 1, 1) + timedelta(days=rng.randint(0, 200))).isoformat()
                + f"T{rng.randint(0,23):02d}:{rng.randint(0,59):02d}:00",
                '{"source":"web"}' if rng.random() > 0.3 else None,
            )
        )
    cur.executemany("INSERT INTO events VALUES (?,?,?,?,?)", events)
    conn.commit()
    conn.close()
    print(f"Seeded SQLite demo at {path}")


def seed_duckdb(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.unlink()
    conn = duckdb.connect(str(path))
    conn.execute(
        """
        CREATE TABLE products (
          product_id INTEGER,
          sku VARCHAR,
          category VARCHAR,
          price DECIMAL(10,2),
          created_at TIMESTAMP,
          tags VARCHAR[]
        );
        CREATE TABLE inventory (
          warehouse_id INTEGER,
          product_id INTEGER,
          qty INTEGER,
          updated_at TIMESTAMP
        );
        """
    )
    rng = random.Random(7)
    categories = ["electronics", "home", "grocery", "apparel", "sports"]
    products = []
    for i in range(1, 301):
        products.append(
            (
                i,
                f"SKU-{i:04d}",
                rng.choice(categories),
                round(rng.uniform(1, 999), 2),
                f"2023-{(i % 12)+1:02d}-15 10:00:00",
                [rng.choice(["new", "sale", "popular", "clearance"]) for _ in range(rng.randint(0, 3))],
            )
        )
    conn.executemany("INSERT INTO products VALUES (?,?,?,?,?,?)", products)

    inventory = []
    for i in range(1, 1001):
        inventory.append(
            (
                rng.randint(1, 5),
                rng.randint(1, 300),
                rng.randint(0, 500),
                f"2024-{(i % 12)+1:02d}-01 08:00:00",
            )
        )
    conn.executemany("INSERT INTO inventory VALUES (?,?,?,?)", inventory)
    conn.close()
    print(f"Seeded DuckDB demo at {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed demo databases")
    parser.add_argument(
        "--out-dir",
        default=str(Path(__file__).resolve().parent / "data"),
        help="Directory for demo DB files",
    )
    args = parser.parse_args()
    out = Path(args.out_dir)
    seed_sqlite(out / "demo.sqlite")
    seed_duckdb(out / "demo.duckdb")


if __name__ == "__main__":
    main()
