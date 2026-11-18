// Mirrors the backend DTOs (com.expensetracker.*). Money arrives as JSON numbers with 2 decimals.

export type Direction = "DEBIT" | "CREDIT";
export type TxnKind = "EXPENSE" | "INCOME" | "TRANSFER" | "REFUND" | "EXCLUDED";
export type TxnStatus = "POSTED" | "PENDING" | "FAILED" | "REVERSED";
export type CategorisationStatus = "PENDING" | "CONFIRMED" | "AUTO";
export type DuplicateStatus = "NONE" | "SUSPECTED";
export type PaymentMethod = "UPI" | "IMPS" | "NEFT" | "RTGS" | "CARD" | "ATM" | "CASH" | "CHEQUE" | "NACH" | "OTHER";
export type SourceType = "MANUAL" | "CSV_IMPORT" | "SMS" | "EMAIL" | "DEMO" | "GMAIL" | "ACCOUNT_AGGREGATOR" | "PHONEPE_STATEMENT";
export type AccountType = "BANK" | "CREDIT_CARD" | "CASH" | "WALLET";
export type CategoryKind = "EXPENSE" | "INCOME";
export type CategorisationMode = "ALWAYS_ASK" | "SMART_SUGGESTION" | "SEMI_AUTOMATIC" | "FULL_AUTOMATIC";
export type RememberScope = "ONLY_THIS" | "MERCHANT" | "NOTE" | "DESCRIPTION" | "APPLY_HISTORICAL";

export interface User {
  id: string;
  email: string;
  displayName: string;
  demo: boolean;
  mfaEnabled?: boolean;
}

export interface Account {
  id: string;
  name: string;
  type: AccountType;
  institution: string | null;
  last4: string | null;
  openingBalance: number;
  openingDate: string | null;
  balance: number;
  transactionCount: number;
  archived: boolean;
  balanceSource: "OPENING_BALANCE" | "COMPUTED_FROM_TRANSACTIONS";
}

export interface Category {
  id: string;
  parentId: string | null;
  name: string;
  path: string;
  kind: CategoryKind;
  group: string | null;
  icon: string | null;
  color: string | null;
  archived: boolean;
  systemDefault: boolean;
  investment: boolean;
  children: Category[];
}

export interface CategoryRef {
  id: string;
  name: string;
  path: string;
  color: string | null;
  icon: string | null;
}

export interface Transaction {
  id: string;
  txnAt: string;
  timeKnown: boolean;
  amount: number;
  currency: string;
  direction: Direction;
  kind: TxnKind;
  status: TxnStatus;
  account: { id: string; name: string; type: AccountType } | null;
  merchant: { id: string; name: string } | null;
  counterparty: string | null;
  description: string | null;
  referenceNumber: string | null;
  paymentMethod: PaymentMethod;
  paymentProvider: string | null;
  category: CategoryRef | null;
  categorisationStatus: CategorisationStatus;
  suggestedCategory: CategoryRef | null;
  suggestionConfidence: number | null;
  suggestionReason: string | null;
  duplicateStatus: DuplicateStatus;
  duplicateOfId: string | null;
  transferGroupId: string | null;
  linkedTransactionId: string | null;
  notes: string | null;
  business: boolean;
  tags: string[];
  splits: { id: string; category: CategoryRef; amount: number; note: string | null }[];
  sources: SourceType[];
  needsReview: boolean;
  version: number;
}

export interface TransactionDetail {
  transaction: Transaction;
  sources: { id: string; type: SourceType; externalId: string; importedAt: string; rawPayload: string | null }[];
  history: { at: string; action: string; actor: string; from: CategoryRef | null; to: CategoryRef | null; detail: string | null }[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CategorySlice {
  categoryId: string;
  name: string;
  color: string;
  icon: string | null;
  amount: number;
  share: number;
  count: number;
}

export interface Insight {
  type: string;
  tone: "neutral" | "positive" | "warning" | "action";
  message: string;
}

export interface DashboardSummary {
  period: { from: string; to: string };
  income: number;
  /** spending: excludes money moved into investment categories */
  expenses: number;
  invested: number;
  grossExpenses: number;
  refunds: number;
  savings: number;
  savingsRate: number | null;
  pendingCategorisationAmount: number;
  pendingCategorisationCountInPeriod: number;
  needsReviewCount: number;
  suspectedDuplicates: number;
  transfersOut: number;
  expenseByCategory: CategorySlice[];
  investedByCategory: CategorySlice[];
  incomeByCategory: CategorySlice[];
  topMerchants: { merchantId: string; name: string; amount: number; count: number }[];
  paymentMethods: { method: PaymentMethod; provider: string | null; amount: number; count: number }[];
  accounts: { totalBalance: number; creditCardOutstanding: number; netWorth: number; investments: number; loans: number; accounts: Account[]; note: string };
  previousPeriod: { from: string; to: string };
  previousIncome: number;
  previousExpenses: number;
  insights: Insight[];
}

export interface MonthPoint {
  month: string;
  income: number;
  expenses: number;
  invested: number;
  savings: number;
}

export interface DayPoint {
  date: string;
  expenses: number;
  income: number;
}

export interface Preferences {
  categorisationMode: CategorisationMode;
  autoConfidenceThreshold: number;
  currency: string;
  theme: "light" | "dark" | "system";
  externalAiConsent: boolean;
}

export interface ProviderDescriptor {
  key: string;
  name: string;
  description: string;
  availability: "AVAILABLE" | "PLANNED" | "UNAVAILABLE";
  connectionMode: "BUILT_IN" | "FILE_UPLOAD" | "WEBHOOK_TOKEN" | "NONE";
  requiresCommercialPartnership: boolean;
  requiresRegulatoryApproval: boolean;
  requiresUserConsent: boolean;
  dataRetrieved: string;
  syncFrequency: string;
  limitation: string | null;
  setupHint: string | null;
}

export interface SyncRecord {
  id: string;
  sourceType: string;
  startedAt: string;
  finishedAt: string | null;
  status: "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED";
  imported: number;
  merged: number;
  suspected: number;
  skipped: number;
  error: string | null;
}

export interface Connection {
  id: string;
  status: "CONNECTED" | "ERROR" | "DISCONNECTED";
  defaultAccountId: string | null;
  tokenHint: string | null;
  connectedAt: string;
  lastSyncAt: string | null;
  nextSyncAt: string | null;
  lastError: string | null;
  permissions: string[];
}

export interface CatalogueEntry {
  provider: ProviderDescriptor;
  connection: Connection | null;
  lastSync: SyncRecord | null;
}

export interface ImportResult {
  layout: string;
  rows: number;
  imported: number;
  merged: number;
  suspectedDuplicates: number;
  alreadyImported: number;
  previouslyDeleted: number;
  updatedWithNotes: number;
  categorisedFromFile: number;
  withNotes: number;
  autoCategorised: number;
  needsReview: number;
  errors: string[];
  warnings: string[];
  syncId: string;
}

export type AssetClass = "MUTUAL_FUND" | "STOCK" | "ETF" | "FIXED_DEPOSIT" | "RECURRING_DEPOSIT" | "PPF" | "EPF" | "NPS" | "BOND" | "GOLD" | "SGB" | "REAL_ESTATE" | "CRYPTO" | "VEHICLE" | "OTHER";
export type AllocationBucket = "EQUITY" | "DEBT" | "HYBRID" | "GOLD" | "REAL_ESTATE" | "CASH" | "OTHER";
export type ValuationMethod = "MARKET_PRICE" | "MANUAL" | "FIXED_RATE";
export type LiabilityType = "HOME_LOAN" | "CAR_LOAN" | "PERSONAL_LOAN" | "EDUCATION_LOAN" | "GOLD_LOAN" | "LOAN_AGAINST_SECURITIES" | "BORROWED_FROM_PERSON" | "OTHER";

export interface Holding {
  id: string;
  assetClass: AssetClass;
  assetClassLabel: string;
  name: string;
  symbol: string | null;
  isin: string | null;
  schemeCode: string | null;
  folio: string | null;
  platform: string | null;
  allocation: AllocationBucket;
  valuationMethod: ValuationMethod;
  units: number | null;
  investedAmount: number | null;
  currentPrice: number | null;
  manualValue: number | null;
  interestRate: number | null;
  compoundingPerYear: number | null;
  startDate: string | null;
  maturityDate: string | null;
  priceAsOf: string | null;
  priceSource: "AMFI" | "BROKER_CSV" | "MANUAL" | "CALCULATED" | "EXCHANGE" | null;
  value: number;
  gain: number | null;
  gainPct: number | null;
  estimated: boolean;
  basis: string;
  notes: string | null;
  archived: boolean;
  version: number;
}

export interface Liability {
  id: string;
  type: LiabilityType;
  name: string;
  lender: string | null;
  principal: number | null;
  outstanding: number;
  interestRate: number | null;
  emi: number | null;
  startDate: string | null;
  endDate: string | null;
  asOf: string;
  repaidPct: number | null;
  notes: string | null;
  archived: boolean;
  version: number;
}

export interface NetWorthItem {
  kind: "ACCOUNT" | "HOLDING" | "LIABILITY";
  id: string;
  name: string;
  detail: string;
  value: number;
  invested: number | null;
  gain: number | null;
  gainPct: number | null;
  source: string;
  estimated: boolean;
  asOf: string | null;
  dayChange: number | null;
  dayChangePct: number | null;
  dayChangeOn: string | null;
}

export interface NetWorthGroup {
  key: string;
  label: string;
  value: number;
  invested: number | null;
  gain: number | null;
  gainPct: number | null;
  items: NetWorthItem[];
  dayChange: number | null;
  dayChangePct: number | null;
}

export interface NetWorthSummary {
  netWorth: number;
  assets: number;
  liabilities: number;
  investedInHoldings: number;
  holdingsValue: number;
  holdingsGain: number;
  holdingsGainPct: number | null;
  assetGroups: NetWorthGroup[];
  liabilityGroups: NetWorthGroup[];
  allocation: { bucket: AllocationBucket; value: number; share: number }[];
  change30d: { since: string; amount: number; pct: number | null } | null;
  changeYtd: { since: string; amount: number; pct: number | null } | null;
  estimatedCount: number;
  pricesNote: string;
  holdingsDayChange: number | null;
  holdingsDayChangePct: number | null;
}

export interface NetWorthPoint {
  date: string;
  assets: number;
  liabilities: number;
  netWorth: number;
}

export interface ListedSecurity {
  isin: string;
  symbol: string;
  exchange: "NSE" | "BSE";
  name: string | null;
  close: number;
  prevClose: number | null;
  priceDate: string;
}

export interface MfScheme {
  schemeCode: string;
  name: string;
  amc: string | null;
  category: string | null;
  isinGrowth: string | null;
  isinReinvest: string | null;
  nav: number | null;
  navDate: string | null;
}

export interface ApiErrorBody {
  error: { code: string; message: string; details: string[] };
}

// ---------------------------------------------------------------- milestone 3

export interface DeleteResult { deleted: number; linkedTransfersUnpaired: number; sourcesDetached: number }
export interface AccountDeleteResult { account: string; transactionsDeleted: number; transfersUnpaired: number }
export interface CategoryDeleteResult { categoriesDeleted: number; transactionsMoved: number; transactionsBackToReview: number }

export type BudgetPeriod = "WEEKLY" | "MONTHLY" | "YEARLY";
export type BudgetState = "ON_TRACK" | "AT_RISK" | "NEAR_LIMIT" | "OVER";
export interface BudgetStatus {
  limit: number; carriedOver: number; available: number; spent: number; remaining: number; pctUsed: number; forecast: number;
  projectedOverspend: number; dailyAllowance: number; state: BudgetState;
}
export interface Budget {
  id: string; name: string; categoryId: string | null; categoryName: string | null; categoryColor: string | null; period: BudgetPeriod;
  amount: number; rollover: boolean; alertThresholds: number[]; archived: boolean; version: number; periodLabel: string;
  periodStart: string; periodEnd: string; daysElapsed: number; daysTotal: number; status: BudgetStatus; recommended: number | null;
  recommendationBasis: string | null; history: { label: string; start: string; limit: number; spent: number }[];
}
export interface BudgetOverview { totalBudgeted: number; totalSpent: number; over: number; atRisk: number; budgets: Budget[]; note: string }

export type RecurringKind = "SUBSCRIPTION" | "BILL" | "EMI" | "RENT" | "INSURANCE" | "SIP" | "INCOME" | "OTHER";
export type RecurringStatus = "SUGGESTED" | "ACTIVE" | "DISMISSED" | "CANCELLED";
export type Frequency = "WEEKLY" | "MONTHLY" | "QUARTERLY" | "YEARLY";
export interface Recurring {
  id: string; name: string; kind: RecurringKind; status: RecurringStatus; income: boolean; frequency: Frequency; amount: number;
  previousAmount: number | null; priceChanged: boolean; amountVaries: boolean; lastPaidOn: string | null; nextDue: string | null;
  daysUntilDue: number | null; overdue: boolean; occurrences: number; monthlyCost: number; annualCost: number; reminderDays: number;
  categoryId: string | null; categoryName: string | null; accountId: string | null; detected: boolean; forgotten: boolean; notes: string | null; version: number;
}
export interface RecurringSummary {
  monthlyCommitted: number; monthlyIncome: number; annualSubscriptions: number; activeSubscriptions: number; suggestions: number;
  dueIn7Days: number; forgotten: number; items: Recurring[]; note: string;
}

export interface CardCycleT { start: string; statementDate: string; dueDate: string }
export type DueState = "NO_DUES" | "PAID" | "PARTIALLY_PAID" | "DUE" | "OVERDUE";
export interface CardDue {
  statementAmount: number; paidSinceStatement: number; remaining: number; minimumDue: number; minimumRemaining: number; state: DueState; daysToDue: number;
}
export interface CreditCard {
  accountId: string; name: string; institution: string | null; last4: string | null; configured: boolean; creditLimit: number | null;
  outstanding: number; available: number | null; utilisationPct: number | null; statementDay: number | null; dueDaysAfter: number | null;
  minDuePct: number | null; minDueFloor: number | null; currentCycle: CardCycleT | null; currentCycleSpend: number | null;
  lastStatement: CardCycleT | null; due: CardDue | null; payments: { transactionId: string; date: string; amount: number; description: string | null; linkedTransfer: boolean }[];
  rewardPoints: number | null; rewardRate: number | null; rewardPointValue: number | null; estimatedPointsThisCycle: number | null;
  estimatedRewardValue: number | null; annualFee: number | null; notes: string | null; explanation: string;
}
export interface CardOverview { totalOutstanding: number; totalLimit: number; overallUtilisationPct: number | null; totalDue: number; cards: CreditCard[] }


export interface Insight { type: string; severity: "INFO" | "WARNING" | "POSITIVE" | "CRITICAL"; title: string; detail: string; calculation: string; link: string | null }
export interface AnalyticsReport {
  month: string; label: string; inProgress: boolean; income: number; spending: number; invested: number; savings: number; savingsRate: number | null;
  previousIncome: number | null; previousSpending: number | null; averageDailySpend: number; pendingCategorisation: number;
  trend: { month: string; label: string; income: number; spending: number; invested: number; savings: number; savingsRate: number | null }[];
  categories: { categoryId: string; name: string; color: string | null; thisMonth: number; lastMonth: number; average3m: number; changePct: number | null }[];
  merchants: { merchantId: string; name: string; amount: number; count: number }[]; insights: Insight[]; note: string;
}

export type FactKind = "CONFIRMED" | "ESTIMATE" | "PENDING";
export interface AssistantAnswer {
  question: string; intent: string; period: string | null; answer: string;
  facts: { label: string; amount: number | null; text: string | null; kind: FactKind }[]; calculation: string[];
  links: { label: string; href: string }[]; followUps: string[]; dataNote: string;
}

export interface AppNotification { id: string; type: string; severity: "INFO" | "WARNING" | "CRITICAL"; title: string; body: string; link: string | null; createdAt: string; read: boolean }
export interface NotificationPrefs {
  emailEnabled: boolean; emailAvailable: boolean; browserEnabled: boolean; budgetAlerts: boolean; billReminders: boolean; subscriptionAlerts: boolean;
  cardReminders: boolean; unusualSpending: boolean; reviewReminders: boolean; largeTxnThreshold: number;
}

export interface PlanInstallment { dueDate: string; amount: number; nav: number | null; navDate: string | null; units: number | null; transactionId: string | null }
export interface InvestmentPlan {
  id: string; kind: "SIP" | "CONTRIBUTION"; holdingId: string; holdingName: string; assetClass: AssetClass | null;
  amount: number; employerAmount: number | null; total: number; dayOfMonth: number; startDate: string; endDate: string | null;
  accountId: string | null; accountName: string | null; active: boolean; nextDue: string | null; lastRunDate: string | null;
  waiting: string | null; installments: PlanInstallment[]; version: number;
}
export interface PriceStatus {
  allowed: boolean; window: "BEFORE_OPEN" | "MARKET_HOURS" | "AFTER_CLOSE"; nextAllowedAt: string | null; reason: string | null;
  latestNavDate: string | null; latestCloseDate: string | null;
}
