# Mosaic

Mosaic is a whiteboard plugin for **Supernote tablets**.

You can write anywhere, make cards, move them around, and connect them together. It also works together with Notipal for sharing content between Supernote Notes, Documents, and the Mosaic canvas.

## Quick Start

### Get the App

Download the latest release from [GitHub Releases](https://github.com/Not-Just-Supernote/sn-plugin-mosaic/releases)), or build it from source.

### Use with Notipal

[Notipal](https://github.com/Not-Just-Supernote/sn-plugin-notipal/) is a separate plugin that can work together with Mosaic.

Start Notipal first, then open Mosaic. This gives you six shared clipboards that stay synced between Documents, Notes, and Mosaic.

You can use them for images, document screenshots, text boxes, AI interactions, and Smart Lasso.

With Smart Lasso, draw a selection directly with your pen. The selected content can be saved as a document screenshot, sent to another device, or inserted back into a Note.

## Features

The canvas, cards, and connections can all hold handwriting.

### Handwriting Canvas

The canvas, each card, and the connection area between cards are separate writing surfaces.

Strokes on a card move with that card. Strokes on a connection stay with the connection. Canvas strokes stay on the canvas.

Mosaic uses system level services for E Ink refresh, so handwriting can show up under the pen tip with very low latency.

There are three pen types. The fineliner keeps a fixed width, the brush reacts to pressure, and the marker comes in black, dark gray, and light gray.

Mosaic calls its geometric shapes **graphx**. You can create lines, ellipses, rectangles, and triangles.

Choose a graphx tool and drag from one corner to another. Once you release the pen, handles appear for resizing and rotation. Rectangles can turn into squares, ellipses into circles, and triangles can switch between isosceles, equilateral, and right triangles.

Graphx has two fill modes: outline and solid fill. With a solid black fill, handwriting inside the shape automatically turns white.

There are three background templates: blank, dot grid, and lined. The whiteboard and Note Cards use the same background.

The canvas can zoom continuously from 10% to 200%.

Between 75% and 150%, you can use two fingers to zoom and move the canvas freely in any direction. Zoom levels outside that range can be changed from the More menu.

Double tap with two fingers to hide or show the top toolbar.

Undo and redo work with strokes, cards, connections, and graphx.

### Ink Adapts to Background

Handwriting automatically changes to white when you write on a dark area.

This works on dark cards and solid black graphx. Once you move back onto a light area, the pen goes back to black.

Mosaic predicts this while the pen is hovering, so the right ink color is already selected when the stroke starts.

### Card System

Text cards support Markdown, including headings, bold, and italic. Long press a card to edit the text with the keyboard.

You can also draw a rough rectangle on the canvas to make a new card. Once the rectangle closes, the strokes inside it are collected into the card.

You can pull a new card out of an existing one too. Start a stroke inside a card and end it somewhere on the canvas. A new connected card will appear where the stroke ends.

Images shared through Notipal arrive as image cards.

Note Cards give you a long vertically scrolling space for handwriting. You can keep scrolling down and writing for as long as you need. Selecting and dragging can grab all strokes below a point at once.

Pull down from the top of a Note Card to go back to the whiteboard. Its thumbnail also updates in the side menu.

Text cards can use either a white or black style. Dark cards use white text and handwriting automatically. Connections are made between cards using the same color, and a card pulled from another card keeps that style.

Drag the corner or edge handles to resize a card. If the text needs more space, the card grows and moves the cards below it down.

Connected cards can also be resized together and matched to the size of a neighboring card.

Long press a card with your finger to pick it up and move it.

### Geometric Connections

Cards can be joined by a geometric bridge between them.

White cards create white connections, and black cards create black connections. The bridge changes its geometry as the cards move.

To connect two cards, draw a stroke from inside one card to inside another. You can also drag one card toward another card of the same color and release when they snap together.

The connection itself is also a writing surface. Handwriting placed there belongs to the connection and stays with it when the cards move.

As the cards move closer or farther apart, the shape of the connection changes with them. Pull the cards far enough apart and the connection breaks. Canvas strokes underneath become visible again, while handwriting that belonged to the connection is cleared.

Bring the cards together again and a new connection can form.

You can also lock a connection. This keeps the connected cards together as one group when moving or scaling them.

### Input

The stylus handles writing, erasing, lasso selection, and graphx drawing.

Touch gestures include two-finger zooming and panning within the supported zoom range, long pressing a card with one finger to drag it, and swiping from the left edge to open the menu. Touch input can also be turned off globally.

The device side slider can follow your eraser or lasso settings. Holding two fingers on the slider switches to the eraser.

Holding the pen side button while hovering switches tools based on your Supernote settings, including lasso and eraser.

## Sync

### Local Network

Enter your computer's address in the menu to connect Mosaic to the companion web page over your local network.

Strokes, cards, and connections sync in real time through WebSocket. Larger files such as images use a separate HTTP connection.

### Online Sharing

You can create a shared room from the menu and send the generated link to someone else.

Mosaic uses version numbers to handle changes from multiple devices and keep the shared canvas in sync.

[![Online](https://i.postimg.cc/VsKBHGTr/Pix-Pin-2026-10-02-08-29-03.png)](https://postimg.cc/5jFCX3fb)