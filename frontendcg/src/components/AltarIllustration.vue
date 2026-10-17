<template>
  <svg class="altar-art" viewBox="0 0 760 480" role="img" aria-label="圜丘坛建筑空间示意">
    <defs>
      <linearGradient id="altar-stone" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#e6ddc9" />
        <stop offset="1" stop-color="#c5b79d" />
      </linearGradient>
      <linearGradient id="altar-ground" x1="0" y1="0" x2="1" y2="1">
        <stop stop-color="#e9e4d6" />
        <stop offset="1" stop-color="#d7d4c4" />
      </linearGradient>
    </defs>
    <path d="M28 315 375 116l354 199-352 158z" fill="url(#altar-ground)" stroke="#c8c1b1" stroke-width="1" />
    <g class="ground-lines" fill="none" stroke="#c6c0b1" stroke-width=".6" opacity=".48">
      <path d="m90 280 350 199M150 246l350 198M210 211l350 198M270 177l350 198M670 280 321 479M610 246 262 444M550 212 202 410M490 177 142 376" />
      <ellipse cx="382" cy="325" rx="255" ry="119" />
      <ellipse cx="382" cy="325" rx="225" ry="104" />
    </g>
    <g class="cypress" fill="#9ba087" opacity=".52">
      <path v-for="tree in trees" :key="tree.x" :d="`M${tree.x} ${tree.y}l-12 34h24z`" />
    </g>
    <g class="altar-platform" stroke="#a99c85" stroke-width="1">
      <path d="M166 325a216 91 0 0 0 432 0v37a216 91 0 0 1-432 0z" fill="#c1b39a" />
      <ellipse cx="382" cy="325" rx="216" ry="91" fill="url(#altar-stone)" />
      <path d="M222 299a160 67 0 0 0 320 0v31a160 67 0 0 1-320 0z" fill="#c4b69c" />
      <ellipse cx="382" cy="299" rx="160" ry="67" fill="#e4dbc8" />
      <path d="M269 274a113 47 0 0 0 226 0v25a113 47 0 0 1-226 0z" fill="#c9baa0" />
      <ellipse cx="382" cy="274" rx="113" ry="47" fill="#e9e0ce" />
      <path d="M331 256a51 21 0 0 0 102 0v19a51 21 0 0 1-102 0z" fill="#c7b89e" />
      <ellipse cx="382" cy="256" rx="51" ry="21" fill="#ece4d3" />
    </g>
    <g fill="none" stroke="#aa9c84" stroke-width=".8" opacity=".68">
      <ellipse cx="382" cy="325" rx="195" ry="82" />
      <ellipse cx="382" cy="299" rx="141" ry="59" />
      <ellipse cx="382" cy="274" rx="94" ry="39" />
      <ellipse cx="382" cy="256" rx="33" ry="13" />
      <path d="M166 325a216 91 0 0 0 432 0M222 299a160 67 0 0 0 320 0M269 274a113 47 0 0 0 226 0" />
    </g>
    <g fill="#d8cdb8" stroke="#a99c85" stroke-width=".7">
      <path d="m354 352 56 0 18 48-92 0z" />
      <path d="m357 323 50 0 12 35-74 0z" />
      <path d="m360 297 44 0 10 30-64 0z" />
      <path d="m363 273 38 0 8 25-54 0z" />
    </g>
    <g fill="none" stroke="#a99c85" stroke-width="1">
      <path d="M358 358h48M355 364h54M353 370h58M350 377h64M347 384h70M357 329h50M355 335h54M353 341h58M361 303h42M359 309h46M363 279h38" />
    </g>
    <ellipse cx="382" cy="256" rx="8" ry="4" fill="#b77d6b" />
  </svg>
</template>

<script setup>
const trees = [
  { x: 66, y: 248 }, { x: 91, y: 237 }, { x: 117, y: 223 }, { x: 143, y: 209 },
  { x: 170, y: 194 }, { x: 198, y: 181 }, { x: 225, y: 167 }, { x: 252, y: 153 },
  { x: 508, y: 153 }, { x: 535, y: 167 }, { x: 562, y: 181 }, { x: 590, y: 194 },
  { x: 617, y: 209 }, { x: 643, y: 223 }, { x: 669, y: 237 }, { x: 694, y: 248 },
]
</script>
