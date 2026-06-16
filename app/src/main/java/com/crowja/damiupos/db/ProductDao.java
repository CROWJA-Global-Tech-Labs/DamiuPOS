package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.Product;

import java.util.ArrayList;
import java.util.List;

public class ProductDao {

    private final DatabaseHelper dbHelper;

    public ProductDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(Product product) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_PRODUCT_NAME, product.getName());
        values.put(DatabaseHelper.COL_HARGA_JUAL, product.getHargaJual());
        values.put(DatabaseHelper.COL_HARGA_MODAL, product.getHargaModal());
        values.put(DatabaseHelper.COL_COLOR, product.getColor());
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_PRODUCTS, values);
    }

    public int update(Product product) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_PRODUCT_NAME, product.getName());
        values.put(DatabaseHelper.COL_HARGA_JUAL, product.getHargaJual());
        values.put(DatabaseHelper.COL_HARGA_MODAL, product.getHargaModal());
        values.put(DatabaseHelper.COL_COLOR, product.getColor());
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_PRODUCTS, values,
                DatabaseHelper.COL_PRODUCT_ID + "=?",
                new String[]{String.valueOf(product.getId())});
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_PRODUCTS, "products",
                DatabaseHelper.COL_PRODUCT_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    public Product getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_PRODUCTS, null,
                DatabaseHelper.COL_PRODUCT_ID + "=?",
                new String[]{String.valueOf(id)},
                null, null, null);
        Product product = null;
        if (cursor.moveToFirst()) {
            product = cursorToProduct(cursor);
        }
        cursor.close();
        return product;
    }

    public List<Product> getAll() {
        List<Product> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_PRODUCTS, null,
                null, null, null, null,
                DatabaseHelper.COL_PRODUCT_NAME + " ASC");
        while (cursor.moveToNext()) {
            list.add(cursorToProduct(cursor));
        }
        cursor.close();
        return list;
    }

    public int getCount() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_PRODUCTS, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    private Product cursorToProduct(Cursor cursor) {
        Product p = new Product();
        p.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PRODUCT_ID)));
        p.setName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PRODUCT_NAME)));
        p.setHargaJual(cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_HARGA_JUAL)));
        p.setHargaModal(cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_HARGA_MODAL)));
        p.setColor(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_COLOR)));
        p.setCreatedAt(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_CREATED_AT)));
        return p;
    }
}
