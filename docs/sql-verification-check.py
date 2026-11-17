import re, uuid, psycopg2, datetime as dt
from decimal import Decimal as D
J='/home/claude/arth/backend/src/main/java/com/expensetracker/'
def src(f): return open(J+f).read()
def block_after(f, marker, nth=0):
    s=src(f); i=s.index(marker)
    for _ in range(nth+1):
        a=s.index('"""', i)+3; b=s.index('"""', a); i=b+3
    return s[a:b]
def const(f, name, env=None):
    s=src(f)
    m=re.search(r'static final String '+name+r'\s*=\s*(.*?);\n', s, re.S)
    expr=m.group(1); out=''
    for tok in re.finditer(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"|([A-Z_]+)', expr, re.S):
        if tok.group(1) is not None: out+=tok.group(1)
        elif tok.group(2) is not None: out+=tok.group(2)
        else: out+=const(f, tok.group(3))
    return out
def q(sql, **p):
    sql=re.sub(r'(?<![:\w]):([a-zA-Z]\w*)', r'%(\1)s', sql)
    sql=re.sub(r'IN \(%\((\w+)\)s\)', r'IN %(\1)s', sql)
    global RC
    cur.execute(sql, {k:(tuple(v) if isinstance(v,list) else v) for k,v in p.items()}); RC=cur.rowcount
    try: return cur.fetchall()
    except psycopg2.ProgrammingError: return None
conn=psycopg2.connect(host='localhost',user='arth',password='arth',dbname='arth_scratch'); conn.autocommit=False
cur=conn.cursor()
psycopg2.extras=__import__('psycopg2.extras').extras; psycopg2.extras.register_uuid()
IST=dt.timezone(dt.timedelta(hours=5,minutes=30))
def ts(d, h=12): return dt.datetime(d.year,d.month,d.day,h,0,tzinfo=IST)
def day(y,m,d): return dt.date(y,m,d)
U=uuid.uuid4(); B=uuid.uuid4(); C=uuid.uuid4()
q("INSERT INTO users(id,email,password_hash,display_name) VALUES(:u,:e,'x','T')",u=U,e=f"{U}@t.in")
q("INSERT INTO financial_accounts(id,user_id,name,type) VALUES(:b,:u,'Bank','BANK'),(:c,:u,'Card','CREDIT_CARD')",b=B,c=C,u=U)
cats={}
def cat(name,kind='EXPENSE',parent=None,inv=False):
    i=uuid.uuid4(); cats[name]=i
    q("INSERT INTO categories(id,user_id,parent_id,name,kind,is_investment) VALUES(:i,:u,:p,:n,:k,:inv)",i=i,u=U,p=cats.get(parent),n=name,k=kind,inv=inv); return i
cat('Food'); cat('Restaurants',parent='Food'); cat('Investments',inv=True); cat('SIP',parent='Investments',inv=True); cat('Bills'); cat('Salary','INCOME')
merch={}
for n in ['netflix','swiggy']:
    merch[n]=uuid.uuid4(); q("INSERT INTO merchants(id,user_id,name,normalized_name) VALUES(:i,:u,:n,:nn)",i=merch[n],u=U,n=n.title(),nn=n)
txns=[]
def tx(d, amt, direction='DEBIT', kind='EXPENSE', cat=None, acct=None, merchant=None, status='CONFIRMED', note=None, tg=None):
    i=uuid.uuid4()
    q("""INSERT INTO transactions(id,user_id,account_id,direction,amount,txn_at,merchant_id,kind,category_id,categorisation_status,notes,note_key,transfer_group_id)
         VALUES(:i,:u,:a,:d,:amt,:t,:m,:k,:c,:s,:n,:nk,:tg)""", i=i,u=U,a=acct or B,d=direction,amt=D(str(amt)),t=ts(d),m=merch.get(merchant),k=kind,
      c=cats.get(cat),s=status if cat or kind not in('EXPENSE','INCOME') else 'PENDING',n=note,nk=note.lower() if note else None,tg=tg)
    txns.append((i,d,amt,direction,kind,cat,merchant)); return i
for m in range(5,12):
    tx(day(2026,m,1),100000,'CREDIT','INCOME','Salary')
    tx(day(2026,m,5),499 if m<11 else 649,cat='Bills',merchant='netflix')
    tx(day(2026,m,10),10000,cat='SIP')
    for d_,a in [(3,350),(12,420),(20,380),(25,450)]:
        if m<11 or d_<17: tx(day(2026,m,d_),a,cat='Restaurants',merchant='swiggy')
big=tx(day(2026,11,14),6000,cat='Restaurants',merchant='swiggy')
tx(day(2026,11,15),100,'CREDIT','REFUND','Restaurants',merchant='swiggy')
split=tx(day(2026,11,8),1000)  # will be split
q("UPDATE transactions SET categorisation_status='CONFIRMED' WHERE id=:i",i=split)
q("INSERT INTO transaction_splits(id,transaction_id,category_id,amount) VALUES(:a,:t,:c1,600),(:b,:t,:c2,400)",a=uuid.uuid4(),b=uuid.uuid4(),t=split,c1=cats['Food'],c2=cats['Bills'])
pending=tx(day(2026,11,9),500)
tx(day(2026,11,11),777,kind='EXCLUDED',cat='Food')
# card
tx(day(2026,10,20),2000,cat='Food',acct=C); tx(day(2026,11,2),3000,cat='Bills',acct=C); tx(day(2026,11,16),1000,cat='Food',acct=C)
g=uuid.uuid4(); tx(day(2026,11,16),2000,'CREDIT','TRANSFER',acct=C,tg=g); tx(day(2026,11,16),2000,'DEBIT','TRANSFER',acct=B,tg=g)

ok=0
def check(label, got, exp):
    global ok
    assert got==exp, f"{label}: got {got!r} expected {exp!r}"; ok+=1; print("ok", label, got)
nov=dict(userId=U, **{'from':ts(day(2026,11,1),0),'to':ts(day(2026,12,1),0)})
# 1 overall spend Nov
exp = D(649)+D(350)+D(420)+D(6000)+D(1000)+D(500)+D(3000)+D(1000) - D(100)   # SIP excluded, excluded kind excluded, card purchases count
check("budget overall Nov", q(const('budget/SpendQueries.java','OVERALL'),**nov)[0][0], exp)
check("budget Food Nov (children + split line - refund)", q(const('budget/SpendQueries.java','CATEGORY'),categoryId=cats['Food'],**nov)[0][0], D(350+420+6000+600-100+1000))
check("budget Restaurants child only", q(const('budget/SpendQueries.java','CATEGORY'),categoryId=cats['Restaurants'],**nov)[0][0], D(350+420+6000-100))
# card statement
r=q(const('creditcard/CreditCardService.java','STATEMENT_SQL'),accountId=C,userId=U,statementEnd=ts(day(2026,11,16),0),cycleStart=ts(day(2026,11,16),0))[0]
check("card balance at statement", r[0], D(-5000)); check("card credits since", r[1], D(2000)); check("card cycle spend", r[2], D(1000))
pays=q(const('creditcard/CreditCardService.java','PAYMENTS_SQL'),accountId=C,userId=U); check("card payments", [(p[2],p[4] is not None) for p in pays], [(D(2000),True)])
# recurring history
rows=q(const('recurring/RecurringService.java','HISTORY'),userId=U,since=ts(day(2025,10,1)))
check("recurring history keys", sorted({r[4] for r in rows if r[4]}), ['netflix','swiggy'])
# analytics
p6=dict(userId=U, **{'from':ts(day(2026,6,1),0),'to':ts(day(2026,12,1),0)})
cs=q(const('analytics/AnalyticsService.java','CAT_SERIES'),**p6)
food_nov=[r[4] for r in cs if r[0]=='2026-11' and r[2]=='Food'][0]
check("analytics Food Nov = budget Food Nov", food_nov, D(350+420+6000+600-100+1000))
check("analytics excludes investments", any(r[2]=='Investments' for r in cs), False)
ms=q(const('analytics/AnalyticsService.java','MERCHANT_SERIES'),**p6)
check("merchant swiggy Nov", [r[3] for r in ms if r[0]=='2026-11' and r[2]=='Swiggy'][0], D(350+420+6000))
un=q(const('analytics/AnalyticsService.java','UNUSUAL'),userId=U,histFrom=ts(day(2026,5,1),0),**{'from':ts(day(2026,11,1),0),'to':ts(day(2026,12,1),0)})
check("unusual flags 6000 swiggy", [(r[3],r[2]) for r in un], [(D(6000),'Restaurants'),(D(3000),'Bills')])
# assistant / report / scheduler queries execute
q(block_after('assistant/AssistantService.java','var row = jdbc.queryForMap('),u=U,m=merch['swiggy'],**{'from':ts(day(2026,11,1),0),'to':ts(day(2026,12,1),0)})
lg=q(block_after('assistant/AssistantService.java','List<Fact> facts = jdbc.query('),u=U,**{'from':ts(day(2026,11,1),0),'to':ts(day(2026,12,1),0)})
check("largest expense", lg[0][1], D(6000))
tr=q(block_after('report/ReportService.java','List<List<Object>> rows = jdbc.query('),u=U,**{'from':ts(day(2026,11,1),0),'to':ts(day(2026,12,1),0)})
check("report has split txn with no category label 'Needs review'?", [r[7] for r in tr if r[5]==D(500)], [None])
q(block_after('notification/AlertScheduler.java','jdbc.query("""'),u=U,since=ts(day(2026,1,1)),threshold=D(5000))
# deleted imports
q("INSERT INTO deleted_imports (user_id, source_type, external_id) VALUES (:u, :t, :e) ON CONFLICT DO NOTHING",u=U,t='CSV_IMPORT',e='abc')
q("INSERT INTO deleted_imports (user_id, source_type, external_id) VALUES (:u, :t, :e) ON CONFLICT DO NOTHING",u=U,t='CSV_IMPORT',e='abc')
check("tombstone", q("SELECT count(*) FROM deleted_imports WHERE user_id = :u AND source_type = :t AND external_id = :e",u=U,t='CSV_IMPORT',e='abc')[0][0], 1)
# notifications dedupe
ins=block_after('notification/NotificationService.java','int inserted = jdbc.update(')
for _ in range(2): q(ins,id=uuid.uuid4(),u=U,type='X',sev='INFO',title='t',body='b',link=None,key='k1')
check("notification dedupe", q("select count(*) from notifications where user_id=:u",u=U)[0][0], 1)
# budget alert
bid=uuid.uuid4(); q("INSERT INTO budgets(id,user_id,name,period,amount,start_date) VALUES(:i,:u,'Food','MONTHLY',5000,'2026-11-01')",i=bid,u=U)
for _ in range(2): q("INSERT INTO budget_alerts (budget_id, period_start, threshold) VALUES (:b, :p, :t) ON CONFLICT DO NOTHING",b=bid,p=day(2026,11,1),t=50)
check("budget alert once", q("select count(*) from budget_alerts where budget_id=:b",b=bid)[0][0], 1)
q("SAVEPOINT s1")
try:
    q("INSERT INTO budgets(id,user_id,name,period,amount,start_date) VALUES(:i,:u,'Food2','MONTHLY',5000,'2026-11-01')",i=uuid.uuid4(),u=U); raise SystemExit("duplicate overall budget allowed")
except psycopg2.errors.UniqueViolation: q("ROLLBACK TO SAVEPOINT s1"); print("ok duplicate overall monthly budget rejected")
# goal holdings upsert
gid=uuid.uuid4(); hid=uuid.uuid4()
q("INSERT INTO financial_goals(id,user_id,name,kind,target_amount) VALUES(:g,:u,'House','HOUSE',1000000)",g=gid,u=U)
q("INSERT INTO holdings(id,user_id,asset_class,name,allocation,valuation_method,manual_value) VALUES(:h,:u,'OTHER','x','OTHER','MANUAL',100)",h=hid,u=U)
up=block_after('goal/GoalService.java','jdbc.update(')
q(up,g=gid,h=hid,s=D(50)); q(up,g=gid,h=hid,s=D(60))
check("goal holding upsert", q("select share_pct from goal_holdings where goal_id=:g",g=gid), [(D('60.00'),)])
# privacy export queries all run
s_=src('privacy/PrivacyService.java')
for name,sql in re.findall(r'EXPORT\.put\("(\w+)", "(.*?)"\);', s_):
    q(sql,u=U)
print("ok export queries", len(re.findall(r'EXPORT\.put', s_)))
# category delete without move: Bills (split touches Bills -> split txn back to review)
repo=src('category/CategoryRepository.java')
def rq(name):
    end=repo.index('int '+name+'('); start=repo.rindex('@Query(value = ', 0, end)
    body=repo[start:end].split(', nativeQuery')[0]
    return ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', body))
ids=[cats['Bills']]
n1=q(rq('resetSplitTransactions'),userId=U,ids=ids); n1=RC
q(rq('deleteSplitsTouching'),userId=U,ids=ids); n2=RC
q(rq('resetTransactions'),userId=U,ids=ids); n3=RC
q(rq('deleteChildren'),userId=U,parentId=cats['Bills']); q(rq('deleteOne'),userId=U,id=cats['Bills']); n4=RC
check("category delete: split txns reset / split rows removed / txns reset / deleted", (n1,n2,n3,n4), (1,2,8,1))
check("split txn now pending", q("select categorisation_status, category_id from transactions where id=:i",i=split), [('PENDING',None)])
# category with children: delete Food moving to Restaurants is invalid (inside); delete Food plain removes child
q(rq('resetSplitTransactions'),userId=U,ids=[cats['Food'],cats['Restaurants']])
q(rq('deleteSplitsTouching'),userId=U,ids=[cats['Food'],cats['Restaurants']])
q(rq('resetTransactions'),userId=U,ids=[cats['Food'],cats['Restaurants']])
q(rq('deleteChildren'),userId=U,parentId=cats['Food']); q(rq('deleteOne'),userId=U,id=cats['Food'])
check("parent+child deleted", q("select count(*) from categories where id in (:a,:b)",a=cats['Food'],b=cats['Restaurants'])[0][0], 0)
# deleting a transaction: sources cascade; transfer counterpart unaffected by FK
q("DELETE FROM transactions WHERE id=:i",i=big)
check("deleted txn gone", q("select count(*) from transactions where id=:i",i=big)[0][0], 0)
# account delete after its transactions
q("DELETE FROM transactions WHERE account_id=:c",c=C); q("DELETE FROM financial_accounts WHERE id=:c",c=C)
print("ok account delete after transactions")
# account deletion of user (privacy): order works
for st in ["DELETE FROM goal_contributions WHERE user_id = :u","DELETE FROM transactions WHERE user_id = :u","DELETE FROM audit_log WHERE user_id = :u","DELETE FROM users WHERE id = :u"]:
    q(st,u=U)
check("user and data gone", q("select (select count(*) from financial_accounts where user_id=:u)+(select count(*) from categories where user_id=:u)+(select count(*) from budgets where user_id=:u)",u=U)[0][0], 0)
conn.rollback()
print("ALL", ok, "checks passed")
