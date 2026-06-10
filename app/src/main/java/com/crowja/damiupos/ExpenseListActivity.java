package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.ExpenseAdapter;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.model.Expense;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * List semua pengeluaran (newest first) + summary harian/bulanan + FAB tambah.
 *
 * <p>Tap item → ExpenseDetailActivity (preview foto + edit/hapus).
 */
public class ExpenseListActivity extends AppCompatActivity
        implements ExpenseAdapter.OnExpenseClickListener {

    private RecyclerView rvExpenses;
    private TextView tvEmpty, tvTotalMonth, tvTotalToday;
    private EditText etSearch;
    private ExpenseAdapter adapter;
    private ExpenseDao expenseDao;

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_expense_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        expenseDao = new ExpenseDao(DatabaseHelper.getInstance(this));

        rvExpenses = findViewById(R.id.rvExpenses);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvTotalMonth = findViewById(R.id.tvTotalMonth);
        tvTotalToday = findViewById(R.id.tvTotalToday);
        etSearch = findViewById(R.id.etSearch);

        adapter = new ExpenseAdapter(this);
        rvExpenses.setLayoutManager(new LinearLayoutManager(this));
        rvExpenses.setAdapter(adapter);

        findViewById(R.id.fabAdd).setOnClickListener(v ->
                startActivity(new Intent(this, ExpenseFormActivity.class)));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                refreshList(s.toString().trim());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList(etSearch.getText().toString().trim());
        refreshSummary();
    }

    private void refreshList(String keyword) {
        List<Expense> list = keyword.isEmpty()
                ? expenseDao.getAll()
                : expenseDao.search(keyword);
        adapter.setItems(list);
        boolean empty = list.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvExpenses.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void refreshSummary() {
        tvTotalMonth.setText("Rp " + NF.format(Math.round(expenseDao.getTotalThisMonth())));
        tvTotalToday.setText("Hari ini: Rp " + NF.format(Math.round(expenseDao.getTotalToday())));
    }

    @Override
    public void onExpenseClick(Expense expense) {
        startActivity(new Intent(this, ExpenseDetailActivity.class)
                .putExtra(ExpenseDetailActivity.EXTRA_EXPENSE_ID, expense.getId()));
    }
}
