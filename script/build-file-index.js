// scripts/build-file-index.js
const fs = require('fs');
const path = require('path');

const DIR = path.join(process.cwd(), 'tracking', 'result');
const OUT = path.join(DIR, 'fileList.json');

function extractDateFromName(name) {
    // 파일명_yyyymmdd.xlsx 에서 yyyymmdd 추출
    const m = name.match(/_(\d{8})\.xlsx$/);
    return m ? m[1] : null;
}

function isXlsx(name) {
    return name.toLowerCase().endsWith('.xlsx');
}

function byDateDesc(a, b) {
    const da = extractDateFromName(a) || '';
    const db = extractDateFromName(b) || '';
    // 최근 날짜가 앞으로 오도록 내림차순
    return db.localeCompare(da);
}

function main() {
    if (!fs.existsSync(DIR)) {
        console.error(`Directory not found: ${DIR}`);
        process.exit(0); // 폴더가 없으면 그냥 종료
    }

    const all = fs.readdirSync(DIR, { withFileTypes: true })
        .filter(d => d.isFile())
        .map(d => d.name)
        .filter(isXlsx);

    // 정렬 (yyyymmdd 기준 내림차순)
    const sorted = all.sort(byDateDesc);

    // JSON 파일로 저장 (배포는 GitHub Pages가 해줌)
    fs.writeFileSync(OUT, JSON.stringify(sorted, null, 2), 'utf8');

    console.log(`Wrote ${OUT} with ${sorted.length} entries.`);
}

main();
