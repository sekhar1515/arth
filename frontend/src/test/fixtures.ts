import type { Account, Category, DashboardSummary, Transaction } from "@/types/api";

export const categories: Category[] = [
  {
    id: "food", parentId: null, name: "Food & Dining", path: "Food & Dining", kind: "EXPENSE", group: "Lifestyle", icon: "utensils", color: "#E11D48",
    archived: false, systemDefault: true, investment: false,
    children: [{ id: "delivery", parentId: "food", name: "Food Delivery", path: "Food & Dining > Food Delivery", kind: "EXPENSE", group: "Lifestyle", icon: "utensils", color: "#E11D48", archived: false, systemDefault: true, investment: false, children: [] }],
  },
  { id: "groceries", parentId: null, name: "Groceries", path: "Groceries", kind: "EXPENSE", group: "Essential", icon: "shopping-basket", color: "#22C55E", archived: false, systemDefault: true, investment: false, children: [] },
  { id: "salary", parentId: null, name: "Salary", path: "Salary", kind: "INCOME", group: "Income", icon: "briefcase", color: "#16A34A", archived: false, systemDefault: true, investment: false, children: [] },
];

export const accounts: Account[] = [
  { id: "hdfc", name: "HDFC Savings", type: "BANK", institution: "HDFC Bank", last4: "4821", openingBalance: 0, openingDate: null, balance: 1000, transactionCount: 1, archived: false, balanceSource: "COMPUTED_FROM_TRANSACTIONS" },
];

export function txn(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: "t1", txnAt: "2026-11-17T14:45:00Z", timeKnown: true, amount: 450, currency: "INR", direction: "DEBIT", kind: "EXPENSE", status: "POSTED",
    account: { id: "hdfc", name: "HDFC Savings", type: "BANK" }, merchant: { id: "m1", name: "Swiggy" }, counterparty: "SWIGGY",
    description: "UPI/DR/431212345678/SWIGGY/YESB/swiggy@ybl/Order", referenceNumber: "431212345678", paymentMethod: "UPI", paymentProvider: "PhonePe",
    category: null, categorisationStatus: "PENDING",
    suggestedCategory: { id: "delivery", name: "Food Delivery", path: "Food & Dining > Food Delivery", color: "#E11D48", icon: "utensils" },
    suggestionConfidence: 0.8, suggestionReason: "Known merchant keyword \"swiggy\"", duplicateStatus: "NONE", duplicateOfId: null,
    transferGroupId: null, linkedTransactionId: null, notes: null, business: false, tags: [], splits: [], sources: ["SMS"], needsReview: true, version: 0,
    ...overrides,
  };
}

export const summary: DashboardSummary = {
  period: { from: "2026-11-01", to: "2026-11-17" }, income: 92000, expenses: 31250, invested: 0, grossExpenses: 31600, refunds: 350, savings: 60750, savingsRate: 66,
  pendingCategorisationAmount: 1250, pendingCategorisationCountInPeriod: 4, needsReviewCount: 5, suspectedDuplicates: 1, transfersOut: 10000,
  investedByCategory: [],
  expenseByCategory: [
    { categoryId: "rent", name: "Rent", color: "#6366F1", icon: "home", amount: 24000, share: 76.8, count: 1 },
    { categoryId: "food", name: "Food & Dining", color: "#E11D48", icon: "utensils", amount: 6000, share: 19.2, count: 14 },
  ],
  incomeByCategory: [], topMerchants: [], paymentMethods: [],
  accounts: { totalBalance: 150000, creditCardOutstanding: 3200, netWorth: 146800, investments: 0, loans: 0, accounts, note: "" },
  previousPeriod: { from: "2026-10-01", to: "2026-10-17" }, previousIncome: 92000, previousExpenses: 29000, insights: [],
};
