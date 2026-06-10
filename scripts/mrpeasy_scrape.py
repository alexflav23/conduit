import json, urllib.request, urllib.error, os, time

key, access = os.environ['MRPEASY_API_KEY'], os.environ['MRPEASY_ACCESS_KEY']

def get(path, rng):
    delay = 5
    for i in range(8):
        try:
            req = urllib.request.Request('https://app.mrpeasy.com/rest/v1/' + path,
                headers={'api_key': key, 'access_key': access, 'Range': rng})
            r = urllib.request.urlopen(req, timeout=60)
            return r.headers.get('content-range', ''), json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                print(f'429, sleeping {delay}s', flush=True)
                time.sleep(delay); delay = min(delay * 2, 120)
            else: raise
        except Exception as e:
            print(f'retry {path}: {e}', flush=True); time.sleep(delay)
    raise RuntimeError(path)

def walk(endpoint, out, slim, idkey):
    seen = set()
    cr, rows = get(endpoint, 'items=0-99')
    total = int(cr.split('/')[-1])
    print(f'{endpoint}: total {total}', flush=True)
    with open(out, 'w') as f:
        start = 0
        while start < total:
            if start > 0:
                time.sleep(0.7)
                _, rows = get(endpoint, f'items={start}-{start+99}')
            if not rows: break
            fresh = 0
            for x in rows:
                i = x.get(idkey)
                if i in seen: continue
                seen.add(i); fresh += 1
                f.write(json.dumps(slim(x), separators=(',', ':')) + '\n')
            if fresh == 0:
                print(f'{endpoint}: page at {start} all duplicates — pagination broken, aborting', flush=True)
                break
            start += len(rows)
            if start % 5000 < 100: print(f'{endpoint}: {start}/{total}', flush=True)
    print(f'{endpoint}: wrote {len(seen)} unique rows', flush=True)

def order_slim(o):
    return {
        'id': o.get('cust_ord_id'), 'code': o.get('code'), 'created': o.get('created'),
        'customer_id': o.get('customer_id'), 'customer_name': o.get('customer_name'),
        'status': o.get('status_txt'), 'part_status': o.get('part_status_txt'),
        'invoice_status': o.get('invoice_status_txt'), 'currency': o.get('currency'),
        'total_price': o.get('total_price'), 'total_price_cur': o.get('total_price_cur'),
        'delivery_date': o.get('delivery_date'), 'actual_delivery_date': o.get('actual_delivery_date'),
        'lines': [{'item_code': l.get('item_code'), 'qty': l.get('quantity'), 'shipped': l.get('shipped'),
                   'price': l.get('item_price'), 'total': l.get('total_price'),
                   'part_status': l.get('part_status_txt'),
                   'actual_delivery_date': l.get('actual_delivery_date')}
                  for l in (o.get('products') or [])]
    }

def shipment_slim(s):
    return {
        'id': s.get('shipment_id'), 'code': s.get('code'), 'created': s.get('created'),
        'order_id': s.get('customer_order_id'), 'order_code': s.get('customer_order_code'),
        'rma_order_id': s.get('rma_order_id'), 'delivery_date': s.get('delivery_date'),
        'status': s.get('status_txt'),
        'lines': [{'item_code': l.get('item_code'), 'qty': l.get('quantity_picked'),
                   'serials': [x.get('serial') for x in (l.get('serials') or [])]}
                  for l in (s.get('products') or [])]
    }

print('brief settle…', flush=True)
time.sleep(5)
base = os.path.expanduser('~/projects/hypervolt/conduit/ingest/mrpeasy')
walk('customer-orders', f'{base}/customer_orders.ndjson', order_slim, 'cust_ord_id')
walk('shipments', f'{base}/shipments.ndjson', shipment_slim, 'shipment_id')
print('SCRAPE COMPLETE', flush=True)
