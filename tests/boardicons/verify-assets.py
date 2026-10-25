#!/usr/bin/env python3
"""Validate frozen public provenance, unchanged PNG bytes, and bounded PNG structure offline."""
import hashlib,json,pathlib,struct,sys,zlib
project=pathlib.Path(__file__).resolve().parents[2]
assets=project/'app/src/main/assets'
manifest=json.loads((assets/'forum-icons/index.json').read_text())
assert manifest['schema']==1 and manifest['publicOnly'] is True
rows=manifest['icons'];assert len(rows)==536
expected=set();fixture=[];checks=0
for row in rows:
 kind,id=row['kind'],row['id'];assert kind in ('forum','collection')
 file=('fid_' if kind=='forum' else 'stid_')+id+'.png'
 assert row['asset']=='forum-icons/'+file
 assert id.lstrip('-').isdigit() and '/' not in id
 expected.add(file);data=(assets/row['asset']).read_bytes()
 assert len(data)==row['bytes'] and len(data)<=262144
 assert hashlib.sha256(data).hexdigest()==row['sha256']
 assert data[:8]==b'\x89PNG\r\n\x1a\n'
 assert struct.unpack('>II',data[16:24])==(row['width'],row['height'])
 assert (row['width'],row['height'])==((180,180) if kind=='forum' else (40,40))
 at=8;idat=[];ended=False
 while at<len(data):
  length=struct.unpack('>I',data[at:at+4])[0];chunk=data[at+4:at+8];payload=data[at+8:at+8+length]
  assert at+length+12<=len(data)
  assert struct.unpack('>I',data[at+8+length:at+12+length])[0]==zlib.crc32(chunk+payload)&0xffffffff
  if chunk==b'IDAT':idat.append(payload)
  if chunk==b'IEND':assert length==0;ended=True
  at+=length+12;checks+=1
 assert ended and at==len(data)
 decompressor=zlib.decompressobj();decoded=decompressor.decompress(b''.join(idat),1048577)
 assert decompressor.eof and len(decoded)<=1048576
 fixture.append('\t'.join(str(row[key]) for key in ('kind','id','url','asset','sha256','bytes','width','height')))
 checks+=8
assert expected=={p.name for p in (assets/'forum-icons').glob('*.png')}
assert '\n'.join(fixture)+'\n'==(project/'tests/boardicons/verified-icons.tsv').read_text()
print('Board icon assets passed: 536 PNG SHA-256 / CRC / bounded decode / provenance checks; '+str(checks)+' assertions, 0 network requests')
