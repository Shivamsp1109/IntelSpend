package com.spendwise.presentation.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.CurrencyRateService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.spendwise.domain.model.Income
import com.spendwise.domain.model.IncomeSource
import com.spendwise.domain.model.Currency

private val incomeSectors = listOf(
    IncomeSector("Active Income", listOf("Salary", "Bonus", "Freelancing", "Gratuity", "Other")),
    IncomeSector("Capital & Investment", listOf("Capital Gains", "Dividends", "Interest", "P2P Lending", "Others")),
    IncomeSector("Rental & Property", listOf("Resident Real Estate", "Commercial Real Estate", "Asset Rentals", "Other")),
    IncomeSector("Business & Passive", listOf("Business Profits", "Royalties", "Affiliate Marketing", "Digital Products", "Other")),
    IncomeSector("Transfer & Government Payments", listOf("Pensions", "Social Security", "Alimony & Child Support", "Grants & Scholarships", "Other")),
    IncomeSector("Miscellaneous", emptyList(), isMiscellaneous = true)
)

private val currencies = listOf("INR", "USD", "EUR", "GBP", "AED", "CAD", "AUD", "SGD", "JPY")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomeBottomSheet(
    incomeDraftsJson: String,
    onDismiss: () -> Unit,
    onDraftsChange: (String) -> Unit,
    onAddIncomes: (List<Income>) -> Unit
) {
    var selectedSector by remember { mutableStateOf<IncomeSector?>(null) }
    val draftState = remember { IncomeDraftState.fromJson(incomeDraftsJson) }
    val rates = remember {
        mutableStateMapOf("INR" to 1.0).apply {
            putAll(draftState.currentRates())
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun persistDrafts() {
        onDraftsChange(draftState.toJson())
    }

    LaunchedEffect(Unit) {
        draftState.usedCurrencies()
            .filter { it != "INR" && draftState.needsFreshRate(it) }
            .forEach { currency ->
                runCatching { CurrencyRateService.rateToInr(currency) }
                    .onSuccess { rate ->
                        draftState.setRate(currency, rate)
                        rates[currency] = rate
                        persistDrafts()
                    }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            selectedSector?.let { sector ->
                IncomeDetailContent(
                    sector = sector,
                    draftState = draftState,
                    rates = rates,
                    onDraftsChange = ::persistDrafts,
                    onBack = { selectedSector = null },
                    onClose = onDismiss,
                    onAddIncome = {
                        val txs = draftState.generateTransactions()
                        if (txs.isNotEmpty()) {
                            onAddIncomes(txs)
                            persistDrafts()
                        }
                        selectedSector = null
                    }
                )
            } ?: IncomeSectorContent(
                onClose = onDismiss,
                onSelect = { selectedSector = it }
            )
        }
    }
}

@Composable
private fun IncomeSectorContent(
    onClose: () -> Unit,
    onSelect: (IncomeSector) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Add Income",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF17102A),
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
        }
    }
    Spacer(Modifier.height(14.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(incomeSectors) { sector ->
            SectorRow(sector = sector, onClick = { onSelect(sector) })
        }
    }
}

@Composable
private fun SectorRow(
    sector: IncomeSector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SpendWiseSoftPurple),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                sector.title,
                modifier = Modifier.weight(1f),
                color = Color(0xFF17102A),
                style = MaterialTheme.typography.titleMedium
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SpendWisePurple)
        }
    }
}

@Composable
private fun IncomeDetailContent(
    sector: IncomeSector,
    draftState: IncomeDraftState,
    rates: MutableMap<String, Double>,
    onDraftsChange: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onAddIncome: () -> Unit
) {
    var rateMessage by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.offset(x = (-10).dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black)
        }
        Text(
            sector.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF17102A),
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
        }
    }
    Spacer(Modifier.height(10.dp))

    if (sector.isMiscellaneous) {
        MiscellaneousIncomeForm(
            entries = draftState.miscellaneousEntries,
            draftState = draftState,
            rates = rates,
            rateMessage = rateMessage,
            onRateMessageChange = { rateMessage = it },
            onDraftsChange = onDraftsChange,
            onAddIncome = onAddIncome
        )
    } else {
        StandardIncomeForm(
            entries = draftState.standardEntries(sector),
            draftState = draftState,
            rates = rates,
            rateMessage = rateMessage,
            onRateMessageChange = { rateMessage = it },
            onDraftsChange = onDraftsChange,
            onAddIncome = onAddIncome
        )
    }
}

@Composable
private fun StandardIncomeForm(
    entries: List<IncomeAmountEntry>,
    draftState: IncomeDraftState,
    rates: MutableMap<String, Double>,
    rateMessage: String?,
    onRateMessageChange: (String?) -> Unit,
    onDraftsChange: () -> Unit,
    onAddIncome: () -> Unit
) {
    val total = entries.sumOf { it.amountInInr(rates) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(entries, key = { it.label }) { entry ->
            IncomeInputRow(
                label = entry.label,
                amount = entry.amount,
                currency = entry.currency,
                draftState = draftState,
                rates = rates,
                onAmountChange = {
                    entry.amount = it
                    onDraftsChange()
                },
                onCurrencyChange = {
                    entry.currency = it
                    onDraftsChange()
                },
                onDraftsChange = onDraftsChange,
                onRateMessageChange = onRateMessageChange
            )
        }
        item {
            TotalRow(total = total, rateMessage = rateMessage)
            Spacer(Modifier.height(16.dp))
            AddIncomeButton(total = total, onAddIncome = onAddIncome)
        }
    }
}

@Composable
private fun MiscellaneousIncomeForm(
    entries: MutableList<MiscIncomeEntry>,
    draftState: IncomeDraftState,
    rates: MutableMap<String, Double>,
    rateMessage: String?,
    onRateMessageChange: (String?) -> Unit,
    onDraftsChange: () -> Unit,
    onAddIncome: () -> Unit
) {
    val context = LocalContext.current
    val total = entries.sumOf { it.amountInInr(rates) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SpendWiseSoftPurple),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = entry.type,
                            onValueChange = {
                                entry.type = it
                                onDraftsChange()
                            },
                            label = { Text("Income type") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = incomeTextFieldColors()
                        )
                        IconButton(
                            onClick = {
                                val isOnlyFirstRow = entries.size == 1 && entries.firstOrNull() == entry
                                val isEmpty = entry.type.isBlank() && entry.amount.isBlank()
                                if (isOnlyFirstRow && isEmpty) {
                                    Toast.makeText(context, "No values to delete", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                entries.remove(entry)
                                if (entries.isEmpty()) {
                                    entries.add(MiscIncomeEntry())
                                }
                                onDraftsChange()
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete income type", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    CurrencyAmountInput(
                        amount = entry.amount,
                        currency = entry.currency,
                        draftState = draftState,
                        rates = rates,
                        onAmountChange = {
                            entry.amount = it
                            onDraftsChange()
                        },
                        onCurrencyChange = {
                            entry.currency = it
                            onDraftsChange()
                        },
                        onDraftsChange = onDraftsChange,
                        onRateMessageChange = onRateMessageChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        item {
            TextButton(onClick = {
                entries.add(MiscIncomeEntry())
                onDraftsChange()
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add another income")
            }
            TotalRow(total = total, rateMessage = rateMessage)
            Spacer(Modifier.height(16.dp))
            AddIncomeButton(total = total, onAddIncome = onAddIncome)
        }
    }
}

@Composable
private fun IncomeInputRow(
    label: String,
    amount: String,
    currency: String,
    draftState: IncomeDraftState,
    rates: MutableMap<String, Double>,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onDraftsChange: () -> Unit,
    onRateMessageChange: (String?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.85f),
            color = Color(0xFF17102A),
            style = MaterialTheme.typography.bodyMedium
        )
        CurrencyAmountInput(
            amount = amount,
            currency = currency,
            draftState = draftState,
            rates = rates,
            onAmountChange = onAmountChange,
            onCurrencyChange = onCurrencyChange,
            onDraftsChange = onDraftsChange,
            onRateMessageChange = onRateMessageChange,
            modifier = Modifier.weight(1.15f)
        )
    }
}

@Composable
private fun CurrencyAmountInput(
    amount: String,
    currency: String,
    draftState: IncomeDraftState,
    rates: MutableMap<String, Double>,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onDraftsChange: () -> Unit,
    onRateMessageChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Surface(
                modifier = Modifier
                    .height(56.dp)
                    .width(76.dp)
                    .clickable { expanded = true },
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                color = SpendWiseSoftPurple
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(currency, color = SpendWisePurple, style = MaterialTheme.typography.labelLarge)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                currencies.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onCurrencyChange(option)
                            if (option == "INR") {
                                onRateMessageChange(null)
                            } else if (draftState.needsFreshRate(option)) {
                                onRateMessageChange("Fetching $option to INR rate...")
                                scope.launch {
                                    runCatching { CurrencyRateService.rateToInr(option) }
                                        .onSuccess {
                                            draftState.setRate(option, it)
                                            rates[option] = it
                                            onDraftsChange()
                                            onRateMessageChange("Stored today's $option to INR rate.")
                                        }
                                        .onFailure {
                                            onCurrencyChange("INR")
                                            onRateMessageChange("Could not fetch $option rate. Enter this value in INR.")
                                        }
                                }
                            } else if (rates[option] == null) {
                                draftState.rateFor(option)?.let { rate ->
                                    rates[option] = rate
                                }
                            }
                        }
                    )
                }
            }
        }
        Divider(
            modifier = Modifier
                .height(42.dp)
                .width(1.dp),
            color = Color.White
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { value ->
                if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d*$"))) {
                    onAmountChange(value)
                }
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            placeholder = { Text("0.00") },
            shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
            colors = incomeTextFieldColors()
        )
    }

}

@Composable
private fun TotalRow(total: Double, rateMessage: String?) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F2FF)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SpendWisePurple.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("INR", color = SpendWisePurple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Total", color = SpendWiseTextMuted, style = MaterialTheme.typography.labelMedium)
                Text(CurrencyFormatter.format(total), color = Color(0xFF17102A), style = MaterialTheme.typography.titleLarge)
                rateMessage?.let {
                    Text(it, color = SpendWiseTextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AddIncomeButton(
    total: Double,
    onAddIncome: () -> Unit
) {
    Button(
        onClick = onAddIncome,
        enabled = total > 0.0,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text("Add Income")
    }
}

private data class IncomeSector(
    val title: String,
    val fields: List<String>,
    val isMiscellaneous: Boolean = false
)

private class IncomeAmountEntry(
    val label: String,
    amount: String = "",
    currency: String = "INR"
) {
    var amount by mutableStateOf(amount)
    var currency by mutableStateOf(currency)

    fun amountInInr(rates: Map<String, Double>): Double {
        return (amount.toDoubleOrNull() ?: 0.0) * (rates[currency] ?: 0.0)
    }
}

private class MiscIncomeEntry {
    val id = System.nanoTime()
    var type by mutableStateOf("")
    var amount by mutableStateOf("")
    var currency by mutableStateOf("INR")

    fun amountInInr(rates: Map<String, Double>): Double {
        return (amount.toDoubleOrNull() ?: 0.0) * (rates[currency] ?: 0.0)
    }
}

@Composable
private fun incomeTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    cursorColor = SpendWisePurple,
    focusedBorderColor = SpendWisePurple,
    unfocusedBorderColor = SpendWiseTextMuted.copy(alpha = 0.35f),
    focusedLabelColor = SpendWisePurple,
    unfocusedLabelColor = SpendWiseTextMuted
)

private class IncomeDraftState(
    private val standardEntries: MutableMap<String, MutableList<IncomeAmountEntry>>,
    val miscellaneousEntries: MutableList<MiscIncomeEntry>,
    private val currencyRates: MutableMap<String, StoredCurrencyRate>
) {
    fun standardEntries(sector: IncomeSector): List<IncomeAmountEntry> {
        return standardEntries.getOrPut(sector.title) {
            mutableStateListOf<IncomeAmountEntry>().apply {
                sector.fields.forEach { add(IncomeAmountEntry(label = it)) }
            }
        }
    }

    fun usedCurrencies(): Set<String> {
        return buildSet {
            standardEntries.values.flatten().forEach { add(it.currency) }
            miscellaneousEntries.forEach { add(it.currency) }
        }
    }

    fun currentRates(): Map<String, Double> {
        val today = CurrencyRateService.dailyCacheDate()
        return currencyRates
            .filterValues { it.date == today }
            .mapValues { it.value.rate }
    }

    fun needsFreshRate(currency: String): Boolean {
        if (currency == "INR") return false
        return currencyRates[currency]?.date != CurrencyRateService.dailyCacheDate()
    }

    fun setRate(currency: String, rate: Double) {
        currencyRates[currency] = StoredCurrencyRate(
            date = CurrencyRateService.dailyCacheDate(),
            rate = rate
        )
    }

    fun rateFor(currency: String): Double? {
        return currencyRates[currency]
            ?.takeIf { it.date == CurrencyRateService.dailyCacheDate() }
            ?.rate
    }

    fun totalInInr(rates: Map<String, Double>): Double {
        return standardEntries.values.flatten().sumOf { it.amountInInr(rates) } +
            miscellaneousEntries.sumOf { it.amountInInr(rates) }
    }

    fun generateTransactions(): List<Income> {
        val now = System.currentTimeMillis()
        val list = mutableListOf<Income>()
        standardEntries.forEach { (sectorTitle, entries) ->
            entries.forEach { entry ->
                val amount = entry.amount.toDoubleOrNull() ?: 0.0
                val sourceEnum = IncomeSource.entries.find { 
                    it.label == entry.label && it.sector.displayName == sectorTitle 
                } ?: IncomeSource.MISCELLANEOUS
                list.add(Income(
                    title = entry.label,
                    amount = amount,
                    currency = Currency.fromCode(entry.currency),
                    source = sourceEnum,
                    date = now
                ))
            }
        }
        miscellaneousEntries.forEach { entry ->
            val amount = entry.amount.toDoubleOrNull() ?: 0.0
            if (entry.type.isNotBlank()) {
                list.add(Income(
                    title = entry.type,
                    amount = amount,
                    currency = Currency.fromCode(entry.currency),
                    source = IncomeSource.MISCELLANEOUS,
                    note = entry.type,
                    date = now
                ))
            }
        }
        return list
    }

    fun clearAmounts() {
        standardEntries.values.flatten().forEach { it.amount = "" }
        miscellaneousEntries.forEach { it.amount = ""; it.type = "" }
        miscellaneousEntries.retainAll { it === miscellaneousEntries.first() }
    }

    fun toJson(): String {
        val root = JSONObject()
        val standard = JSONObject()
        standardEntries.forEach { (sector, entries) ->
            standard.put(sector, JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("label", entry.label)
                        put("amount", entry.amount)
                        put("currency", entry.currency)
                    })
                }
            })
        }
        root.put("standard", standard)
        root.put("miscellaneous", JSONArray().apply {
            miscellaneousEntries.forEach { entry ->
                put(JSONObject().apply {
                    put("type", entry.type)
                    put("amount", entry.amount)
                    put("currency", entry.currency)
                })
            }
        })
        root.put("rates", JSONObject().apply {
            currencyRates.forEach { (currency, storedRate) ->
                put(currency, JSONObject().apply {
                    put("date", storedRate.date)
                    put("rate", storedRate.rate)
                })
            }
        })
        return root.toString()
    }

    companion object {
        fun fromJson(value: String): IncomeDraftState {
            return runCatching {
                val root = JSONObject(value)
                val standardObject = root.optJSONObject("standard") ?: JSONObject()
                val ratesObject = root.optJSONObject("rates") ?: JSONObject()
                val standard = mutableMapOf<String, MutableList<IncomeAmountEntry>>()
                val rates = mutableMapOf<String, StoredCurrencyRate>()
                ratesObject.keys().forEach { currency ->
                    val saved = ratesObject.optJSONObject(currency)
                    if (saved != null) {
                        rates[currency] = StoredCurrencyRate(
                            date = saved.optString("date"),
                            rate = saved.optDouble("rate", 0.0)
                        )
                    }
                }
                incomeSectors.filterNot { it.isMiscellaneous }.forEach { sector ->
                    val savedEntries = standardObject.optJSONArray(sector.title)
                    standard[sector.title] = mutableStateListOf<IncomeAmountEntry>().apply {
                        sector.fields.forEachIndexed { index, label ->
                            val saved = savedEntries?.optJSONObject(index)
                            add(
                                IncomeAmountEntry(
                                    label = label,
                                    amount = saved?.optString("amount").orEmpty(),
                                    currency = saved?.optString("currency", "INR") ?: "INR"
                                )
                            )
                        }
                    }
                }
                val miscArray = root.optJSONArray("miscellaneous")
                val misc = mutableStateListOf<MiscIncomeEntry>().apply {
                    if (miscArray != null && miscArray.length() > 0) {
                        repeat(miscArray.length()) { index ->
                            val saved = miscArray.optJSONObject(index)
                            add(
                                MiscIncomeEntry().apply {
                                    type = saved?.optString("type").orEmpty()
                                    amount = saved?.optString("amount").orEmpty()
                                    currency = saved?.optString("currency", "INR") ?: "INR"
                                }
                            )
                        }
                    } else {
                        add(MiscIncomeEntry())
                    }
                }
                IncomeDraftState(standard, misc, rates)
            }.getOrElse {
                IncomeDraftState(
                    standardEntries = mutableMapOf(),
                    miscellaneousEntries = mutableStateListOf(MiscIncomeEntry()),
                    currencyRates = mutableMapOf()
                )
            }
        }
    }
}

private data class StoredCurrencyRate(
    val date: String,
    val rate: Double
)
