#!/usr/bin/env python3
"""Independent finite enumeration for four newly reviewed genetics/translation answers."""
import json,itertools
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
bank={q['id']:q for q in json.loads((ROOT/'content/questions.json').read_text())}
def q(n):return bank[f'UP-9ba2aeb98098-{n:04}']
def children(female,male):return {tuple(sorted((x,y))) for x in female for y in male}
# The four F1 matings all permit a red-eyed son; no other offered genotype is shared.
females=[('R','R'),('R','r')];males=[('R','Y'),('r','Y')]
sets=[children(f,m) for f in females for m in males];common=set.intersection(*sets)
assert common=={tuple(sorted(('R','Y')))} and q(15)['answer']==1
# A father ill at one X-linked locus transmits that allele to every daughter.
# Recombination permits all four maternal haplotypes; normal homozygous at both never occurs.
haplotypes=list(itertools.product((0,1),repeat=2));daughters=[]
for paternal in [(0,1),(1,0)]:
 for maternal in haplotypes:daughters.append(tuple(tuple(sorted((a,b))) for a,b in zip(paternal,maternal)))
assert ((1,1),(1,1)) not in daughters
assert ((0,1),(0,1)) in daughters and ((0,0),(1,1)) in daughters and ((0,0),(0,1)) in daughters
assert q(11)['answer']==0
# Hemophilia: every daughter matches mother's phenotype and a proper subset of sons
# differ from father only for a carrier healthy mother and a healthy father.
fits=[]
for mom in [(0,0),(0,1),(1,1)]:
 for dad in [0,1]:
  girlhealthy=[bool(a or dad) for a in mom];boydifferent=[a!=dad for a in mom]
  if all(h==bool(max(mom)) for h in girlhealthy) and any(boydifferent) and not all(boydifferent):fits.append((mom,dad))
assert fits==[((0,1),1)] and q(44)['answer']==0
# Complement each given template, locate the first AUG in-frame from transcript start,
# and count residues until the first in-frame stop; no answer rationale is used.
lengths=[];rnas=[]
for option in q(31)['options']:
 dna=''.join(c for c in option if c in 'ACGT');assert len(dna)==18
 rna=dna.translate(str.maketrans('ACGT','UGCA'));rnas.append(rna)
 start=rna.find('AUG');assert start>=0
 codons=[rna[i:i+3] for i in range(start,len(rna),3)]
 stop=next(i for i,c in enumerate(codons) if c in {'UAA','UAG','UGA'});lengths.append(stop)
assert lengths==[3,2,2,1] and q(31)['answer']==lengths.index(max(lengths))
print(json.dumps({'passed':True,'enumerations':['1405/11 two X-linked loci','1405/15 four F1 matings','1405/44 all hemophilia parents','1405/31 template-complement translation'],'peptideLengths':lengths,'rnas':rnas,'scope':'Checks four derivations; does not certify all biological content or an official key.'},indent=2))
